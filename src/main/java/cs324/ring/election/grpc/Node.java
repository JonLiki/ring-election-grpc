package cs324.ring.election.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Minimal ring-based leader election over gRPC. - Register node (port 5000)
 * owns membership and rewiring. - Each node tracks its successor (nextPort) and
 * sends heartbeats. - On successor failure, node reports to register; register
 * rewires predecessor -> successor. - Election: LCR (max nodeId wins). - Ring
 * is printed by IDs: Node A --> Node B --> ... --> Node A.
 */
public class Node extends NodeServiceGrpc.NodeServiceImplBase {

    private static final int REGISTER_PORT = 5000;
    private static final long HEARTBEAT_INTERVAL_MS = 3000;
    private static final int MAX_MISSED_PINGS = 3;
    private static final long RPC_DEADLINE_SECONDS = 2;

    private final int id;
    private final int port;
    private final boolean isRegister;
    private volatile int nextPort = -1;
    private volatile int leaderId = -1;
    private final AtomicBoolean isRegistered = new AtomicBoolean(false);

    // Register node membership (ordered ring)
    private final List<NodeInfo> registeredNodes = Collections.synchronizedList(new ArrayList<>());

    private Server server;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> heartbeatTask;
    private final AtomicInteger missedPings = new AtomicInteger(0);

    private static class NodeInfo {

        final int nodeId;
        final int port;
        int nextPort;

        NodeInfo(int nodeId, int port) {
            this.nodeId = nodeId;
            this.port = port;
            this.nextPort = -1;
        }
    }

    public Node(int id, int port, boolean isRegister) {
        this.id = id;
        this.port = port;
        this.isRegister = isRegister;
    }

    // ---- lifecycle ----
    public void startServer() throws IOException {
        server = ServerBuilder.forPort(port).addService(this).build().start();
        log("Started on port " + port);
        if (!isRegister && isRegistered.get()) {
            startHeartbeat();
        }
    }

    public void stopServer() {
        if (server != null) {
            server.shutdown();
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        scheduler.shutdownNow();
        log("Server stopped.");
    }

    // ---- heartbeat ----
    private void startHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            return;
        }
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            int target = nextPort;
            if (target <= 0) {
                return;
            }
            if (sendPing(target)) {
                missedPings.set(0);
            } else if (missedPings.incrementAndGet() >= MAX_MISSED_PINGS) {
                log("Successor " + target + " suspected DOWN -> reporting");
                missedPings.set(0);
                reportCrashToRegister(target);
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private boolean sendPing(int targetPort) {
        ManagedChannel ch = null;
        try {
            ch = channel(targetPort);
            var stub = NodeServiceGrpc.newBlockingStub(ch).withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS);
            stub.ping(NodeProto.PingRequest.newBuilder().setFromPort(this.port).build());
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (ch != null) {
                ch.shutdownNow();
            }
        }
    }

    private void reportCrashToRegister(int deadPort) {
        if (this.port == REGISTER_PORT) {
            repairRingAfterCrash(deadPort);
            return;
        }
        ManagedChannel ch = null;
        try {
            ch = channel(REGISTER_PORT);
            var stub = NodeServiceGrpc.newBlockingStub(ch).withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS);
            var resp = stub.reportCrash(NodeProto.CrashReportRequest.newBuilder()
                    .setDeadPort(deadPort).setReporterId(this.id).setReporterPort(this.port).build());
            log("Register repair: " + resp.getInfo());
        } catch (Exception e) {
            log("Crash report failed: " + e.getMessage());
        } finally {
            if (ch != null) {
                ch.shutdownNow();
            }
        }
    }

    // ---- rpc send helpers (per-call channels) ----
    private ManagedChannel channel(int p) {
        return ManagedChannelBuilder.forAddress("localhost", p).usePlaintext().build();
    }

    private void sendElectionTo(int targetPort, int candidateId, int originId) {
        if (targetPort <= 0) {
            return;
        }
        ManagedChannel ch = null;
        try {
            ch = channel(targetPort);
            var stub = NodeServiceGrpc.newBlockingStub(ch).withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS);
            stub.sendElection(NodeProto.ElectionRequest.newBuilder()
                    .setCandidateId(candidateId).setOriginId(originId).build());
            log("Election(c=" + candidateId + ") -> " + targetPort);
        } catch (Exception e) {
            log("Election send failed -> " + targetPort + ": " + e.getMessage());
        } finally {
            if (ch != null) {
                ch.shutdownNow();
            }
        }
    }

    private void sendLeaderTo(int targetPort, int leaderId, int originId) {
        if (targetPort <= 0) {
            return;
        }
        ManagedChannel ch = null;
        try {
            ch = channel(targetPort);
            var stub = NodeServiceGrpc.newBlockingStub(ch).withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS);
            stub.sendLeader(NodeProto.LeaderRequest.newBuilder()
                    .setLeaderId(leaderId).setOriginId(originId).build());
        } catch (Exception e) {
            log("Leader send failed -> " + targetPort + ": " + e.getMessage());
        } finally {
            if (ch != null) {
                ch.shutdownNow();
            }
        }
    }

    private void sendSetNextTo(int targetPort, int nextPortVal) {
        ManagedChannel ch = null;
        try {
            ch = channel(targetPort);
            var stub = NodeServiceGrpc.newBlockingStub(ch).withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS);
            stub.setNext(NodeProto.NextPortRequest.newBuilder()
                    .setNextPort(nextPortVal).setFromRegister(REGISTER_PORT).build());
            log("SetNext(" + idOf(targetPort) + " -> " + idOf(nextPortVal) + ")");
        } catch (Exception e) {
            log("SetNext failed -> " + targetPort + ": " + e.getMessage());
        } finally {
            if (ch != null) {
                ch.shutdownNow();
            }
        }
    }

    // ---- service impls ----
    @Override
    public void register(NodeProto.RegisterRequest req, StreamObserver<NodeProto.RegisterResponse> out) {
        if (!isRegister) {
            out.onError(Status.FAILED_PRECONDITION.withDescription("Not register").asRuntimeException());
            return;
        }
        int newId = req.getNodeId(), newPort = req.getPort();
        if (newPort <= 0 || (newPort == REGISTER_PORT && newId != 0) || (newId <= 0 && newPort != REGISTER_PORT)) {
            out.onError(Status.INVALID_ARGUMENT.withDescription("Invalid id/port").asRuntimeException());
            return;
        }

        synchronized (registeredNodes) {
            if (registeredNodes.stream().anyMatch(n -> n.nodeId == newId || n.port == newPort)) {
                out.onError(Status.ALREADY_EXISTS.withDescription("Duplicate id/port").asRuntimeException());
                return;
            }
            NodeInfo node = new NodeInfo(newId, newPort);
            if (registeredNodes.isEmpty()) {
                node.nextPort = newPort; // single node -> self
            } else {
                NodeInfo last = registeredNodes.get(registeredNodes.size() - 1);
                NodeInfo first = registeredNodes.get(0);
                last.nextPort = newPort;
                node.nextPort = first.port;
                sendSetNextTo(last.port, newPort);
            }
            registeredNodes.add(node);

            out.onNext(NodeProto.RegisterResponse.newBuilder().setNextPort(node.nextPort).build());
            out.onCompleted();

            log("Registered Node " + newId + " (port " + newPort + "); next=Node " + idOf(node.nextPort));
            printRingIds();
        }
    }

    @Override
    public void setNext(NodeProto.NextPortRequest req, StreamObserver<NodeProto.NextPortResponse> out) {
        if (req.getFromRegister() != REGISTER_PORT) {
            out.onNext(NodeProto.NextPortResponse.newBuilder().setNodeId(-1).build());
            out.onCompleted();
            return;
        }
        this.nextPort = req.getNextPort();
        missedPings.set(0);
        if (heartbeatTask == null || heartbeatTask.isCancelled()) {
            startHeartbeat();
        }
        out.onNext(NodeProto.NextPortResponse.newBuilder().setNodeId(this.id).build());
        out.onCompleted();
    }

    @Override
    public void ping(NodeProto.PingRequest req, StreamObserver<NodeProto.PingResponse> out) {
        out.onNext(NodeProto.PingResponse.newBuilder().setNodeId(this.id).build());
        out.onCompleted();
    }

    @Override
    public void sendElection(NodeProto.ElectionRequest req, StreamObserver<NodeProto.ElectionResponse> out) {
        int candidateId = req.getCandidateId(), originId = req.getOriginId();
        out.onNext(NodeProto.ElectionResponse.newBuilder().setNodeId(this.id).build());
        out.onCompleted();

        if (isRegister) {
            if (nextPort > 0) {
                forwardElection(Math.max(id, candidateId), originId);
            }
            return;
        }
        if (!isRegistered.get()) {
            return;
        }

        if (originId == this.id) {
            this.leaderId = candidateId;
            log("ELECTION COMPLETE: Leader=Node " + leaderId);
            forwardLeader(leaderId, this.id);
        } else {
            forwardElection(Math.max(this.id, candidateId), originId);
        }
    }

    @Override
    public void sendLeader(NodeProto.LeaderRequest req, StreamObserver<NodeProto.LeaderResponse> out) {
        int newLeader = req.getLeaderId(), originId = req.getOriginId();
        this.leaderId = newLeader;
        out.onNext(NodeProto.LeaderResponse.newBuilder().setNodeId(this.id).build());
        out.onCompleted();

        if (this.id == originId) {
            log("LEADER CONFIRMED: Node " + newLeader);
        } else {
            log("Leader=Node " + newLeader);
            forwardLeader(newLeader, originId);
        }
    }

    @Override
    public void reportCrash(NodeProto.CrashReportRequest req, StreamObserver<NodeProto.CrashReportResponse> out) {
        if (!isRegister) {
            out.onError(Status.FAILED_PRECONDITION.withDescription("Not register").asRuntimeException());
            return;
        }
        boolean repaired;
        synchronized (registeredNodes) {
            repaired = repairRingAfterCrash(req.getDeadPort());
        }
        String info = (repaired ? "Rewired ring after removing " : "Unknown port ") + req.getDeadPort();
        printRingIds();
        out.onNext(NodeProto.CrashReportResponse.newBuilder().setRepaired(repaired).setInfo(info).build());
        out.onCompleted();
    }

    // ---- forwarding ----
    private void forwardElection(int candidateId, int originId) {
        if (nextPort > 0) {
            sendElectionTo(nextPort, candidateId, originId);
        }
    }

    private void forwardLeader(int leaderId, int originId) {
        if (nextPort > 0) {
            sendLeaderTo(nextPort, leaderId, originId);
        }
    }

    // ---- register-side repair ----
    private boolean repairRingAfterCrash(int deadPort) {
        if (!isRegister) {
            return false;
        }
        synchronized (registeredNodes) {
            int idx = -1;
            for (int i = 0; i < registeredNodes.size(); i++) {
                if (registeredNodes.get(i).port == deadPort) {
                    idx = i;
                    break;
                }
            }
            if (idx == -1) {
                return false;
            }

            NodeInfo removed = registeredNodes.remove(idx);
            if (registeredNodes.isEmpty()) {
                log("Removed Node " + removed.nodeId + "; ring empty");
                return true;
            }
            if (registeredNodes.size() == 1) {
                NodeInfo only = registeredNodes.get(0);
                only.nextPort = only.port;
                sendSetNextTo(only.port, only.port);
                log("Removed Node " + removed.nodeId + "; single node ring -> self");
                return true;
            }
            int predIdx = (idx - 1 + registeredNodes.size()) % registeredNodes.size();
            int succIdx = (idx) % registeredNodes.size();
            NodeInfo pred = registeredNodes.get(predIdx), succ = registeredNodes.get(succIdx);
            pred.nextPort = succ.port;
            sendSetNextTo(pred.port, succ.port);
            log("Removed Node " + removed.nodeId + "; rewired Node " + pred.nodeId + " -> Node " + succ.nodeId);
            return true;
        }
    }

    // ---- ring display (IDs) ----
    private Integer idOf(int p) {
        synchronized (registeredNodes) {
            for (NodeInfo n : registeredNodes) {
                if (n.port == p) {
                    return n.nodeId;
                }
            }
        }
        return null;
    }

    private NodeInfo findByPort(int p) {
        synchronized (registeredNodes) {
            for (NodeInfo n : registeredNodes) {
                if (n.port == p) {
                    return n;
                }
            }
        }
        return null;
    }

    private String ringIds() {
        synchronized (registeredNodes) {
            if (registeredNodes.isEmpty()) {
                return "[]";
            }
            NodeInfo start = registeredNodes.get(0), cur = start;
            StringBuilder sb = new StringBuilder("Node ").append(start.nodeId);
            for (int i = 0; i < registeredNodes.size() - 1; i++) {
                NodeInfo next = findByPort(cur.nextPort);
                if (next == null) {
                    break;
                }
                sb.append(" --> Node ").append(next.nodeId);
                cur = next;
            }
            return sb.append(" --> Node ").append(start.nodeId).toString();
        }
    }

    private void printRingIds() {
        if (isRegister) {
            log("RING: " + ringIds());
        }
    }

    // ---- logging ----
    private void log(String msg) {
        System.out.println("[NODE " + id + "] " + msg);
    }

    // ---- main ----
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Is this the register node? (true/false): ");
        boolean isRegisterNode = Boolean.parseBoolean(sc.nextLine().trim());

        int nodeId, port;
        if (isRegisterNode) {
            nodeId = 0;
            port = REGISTER_PORT;
            System.out.println("Register node starting on port 5000...");
        } else {
            System.out.print("Enter Node ID: ");
            nodeId = sc.nextInt();
            System.out.print("Enter Port: ");
            port = sc.nextInt();
            if (port == REGISTER_PORT) {
                System.err.println("Port 5000 reserved for register");
                return;
            }
            sc.nextLine(); // consume newline
        }

        try {
            Node node = new Node(nodeId, port, isRegisterNode);
            node.startServer();

            if (isRegisterNode) {
                System.out.println("Register node running on port 5000. Waiting for nodes...");
                Thread.currentThread().join();
            } else {
                node.connectWithRetry(5, 3000);
                System.out.println("\nCommands: 1=Start Election, 2=Exit");
                while (true) {
                    String cmd = sc.nextLine().trim();
                    if ("1".equals(cmd)) {
                        node.log("Starting election...");
                        node.sendElectionTo(node.nextPort, nodeId, nodeId);
                    } else if ("2".equals(cmd)) {
                        node.stopServer();
                        System.exit(0);
                    } else {
                        System.out.println("Type 1 or 2");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Node failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ---- registration client ----
    private void connectWithRetry(int maxRetries, int delayMs) {
        int tries = 0;
        while (tries < maxRetries) {
            ManagedChannel ch = null;
            try {
                ch = channel(REGISTER_PORT);
                var stub = NodeServiceGrpc.newBlockingStub(ch).withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS);
                var resp = stub.register(NodeProto.RegisterRequest.newBuilder().setNodeId(id).setPort(port).build());
                this.nextPort = resp.getNextPort();
                this.isRegistered.set(true);
                log("Registered. nextPort=" + nextPort);
                startHeartbeat();
                return;
            } catch (Exception e) {
                tries++;
                log("Register attempt " + tries + " failed: " + e.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (ch != null) {
                    ch.shutdownNow();
                }
            }
        }
        log("Failed to register after retries.");
        System.exit(1);
    }
}
