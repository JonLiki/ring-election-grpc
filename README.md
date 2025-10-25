# Distributed Ring Leader Election (LCR Algorithm using gRPC)

![Java](https://img.shields.io/badge/Java-17+-orange)
![gRPC](https://img.shields.io/badge/gRPC-Enabled-blue)
![Distributed System](https://img.shields.io/badge/System-Distributed-brightgreen)
![Status](https://img.shields.io/badge/Status-Academic%20Project-success)

This project implements a **distributed Leader Election protocol** based on the **LCR (LeLann–Chang–Roberts)** algorithm using **gRPC**.  
Nodes form a **logical ring**, each knowing only its direct successor.  
The system supports **dynamic joining**, **leader election**, and **automatic failure recovery**.

---

## 👥 Group Members

| Name | Student ID | Role |
|------|------------|------|
| **Sione Likiliki** | S11204363 | Design, Implementation and Testing |
| **Seiloni Tuungahala** | S11180069 | Design and Testing |
| **Lanuongo Gutteinbeil** | S11235322 | Write-up and Design |
| **Fasi Tangataevaha** | S11192119 | Write-up and Testing |

---

## 🚀 Features

- LCR Ring-based Leader Election
- Fault Detection via Heartbeats
- Self-Healing Ring (auto-repair when a node fails)
- Dynamic Node Registration (via Register Node)
- Leader Announcement Broadcast

---

## 🏛 System Overview

### Register Node
- Runs on port `5000`
- Maintains ordered list of nodes in ring
- Repairs ring when a node fails

### Regular Nodes
- Join ring through register node
- Monitor successor via heartbeat
- Participate in election and forward messages

---

## 🔁 Leader Election (LCR Algorithm)

1. Any node can start the election.
2. It sends:
   ```
   ELECTION(candidateId = self.id, originId = self.id)
   ```
3. Each node forwards the **maximum candidateId** seen.
4. When the message returns to the originator, that node becomes the **Leader**.
5. Leader sends `LEADER(leaderId)` announcement around the ring.

---

## 💀 Failure Detection & Repair

- Each node `PING`s its successor every 3 seconds.
- If successor fails to respond 3 times:
  - It reports failure to register node.
  - Register removes failed node & relinks ring.
  - Register instructs predecessor to update nextPort.

Ring continues operation — no restart required.

---

## 🖥️ How to Run

### 1) Build Project
```sh
mvn clean package
```

### 2) Start Register Node (first terminal)
```sh
mvn org.codehaus.mojo:exec-maven-plugin:3.1.0:java "-Dexec.mainClass=cs324.ring.election.grpc.Node"
```
```
Is this the register node? (true/false): true
```

### 3) Start Regular Nodes (new terminal per node)
```sh
mvn org.codehaus.mojo:exec-maven-plugin:3.1.0:java "-Dexec.mainClass=cs324.ring.election.grpc.Node"
```
```
Is this the register node? (true/false): false
Enter Node ID: 1
Enter Port: 5001
```

### 4) Start Election
```
1
```

### 5) Exit Node
```
2
```

---

## 🧩 Visual Examples

### Ring Structure
![Ring Example](ring_example.png)

### Election Flow
![Election Flow](election_flow.png)

### Failure Recovery
![Failure Repair](failure_repair.png)

---

## 📁 Project Structure
```
src/
 ├── main/java/cs324/ring/election/grpc/Node.java     # Node logic
 └── main/proto/node.proto                            # gRPC definitions
pom.xml                                               # Maven config
```

---

## 📌 Future Improvements
- Backup register node for full fault tolerance
- Leader heartbeat monitoring
- Persistent connections (stream-based gRPC)
- Leader-managed synchronization tasks

---

## 📄 License (MIT)
```
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the “Software”), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
