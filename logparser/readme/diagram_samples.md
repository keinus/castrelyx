# Diagram Samples

이 파일은 Logparser 문서 화면에서 Mermaid와 StarUML/PlantUML류 다이어그램 렌더링을 확인하기 위한 샘플입니다. 다이어그램 내용은 현재 구현된 pipeline, input/output adapter, Castrelyx agent ingest 경로를 기준으로 합니다.

## Mermaid: Runtime Pipeline

```mermaid
flowchart LR
  subgraph Inputs["Input adapters"]
    File["FileInputAdapter"]
    Tcp["TcpInputAdapter"]
    TlsTcp["TlsTcpInputAdapter"]
    Http["HttpInputAdapter"]
    Https["HttpsInputAdapter"]
    KafkaIn["KafkaInputAdapter"]
    Snmp["SnmpInputAdapter"]
    Rabbit["RabbitMqInputAdapter"]
    TlsRabbit["TlsRabbitMqInputAdapter"]
    Agent["TcpMtlsGzipInputAdapter"]
  end

  Inputs --> MessageDispatcher["MessageDispatcher"]
  MessageDispatcher --> ProcessingDispatcher["ProcessingDispatcher"]
  ProcessingDispatcher --> Parser["Parser\nJson/Grok/Regex/RFC3164/RFC5424/HTTP"]
  Parser --> Transform["Transform\nFilter/AddProperty/RemoveProperty"]
  Transform --> Structure["StructuredTransformService"]

  Structure --> Console["ConsoleOutputAdapter"]
  Structure --> TcpOut["TcpOutputAdapter"]
  Structure --> HttpOut["HttpOutputAdapter"]
  Structure --> KafkaOut["KafkaOutputAdapter"]
  Structure --> OpenSearch["OpenSearchOutputAdapter"]
  Structure --> RabbitOut["RabbitMQAdapter"]
  Structure --> MariaDb["MariaDbOutputAdapter"]
  Structure --> ClickHouse["ClickHouseOutputAdapter"]

  ProcessingDispatcher -. broadcast .-> LiveTail["LiveTailService\n/ws/tail"]
```

## Mermaid: TLS Input Choices

```mermaid
flowchart TB
  Client["External sender"] --> TcpTls["TlsTcpInputAdapter\nnewline TCP over TLS"]
  Webhook["Webhook sender"] --> Https["HttpsInputAdapter\nHTTP request over TLS"]
  RabbitBroker["RabbitMQ broker"] --> TlsRabbit["TlsRabbitMqInputAdapter\nAMQP over TLS"]
  Agent["Castrelyx agent"] --> MtlsGzip["TcpMtlsGzipInputAdapter\nmTLS + framed gzip JSON"]

  TcpTls --> CommonTls["TlsConfigSupport\nserver socket"]
  Https --> CommonTls
  TlsRabbit --> ClientTls["TlsConfigSupport\nclient SSLContext"]
  MtlsGzip --> Dedicated["Dedicated mTLS server\nCN == source_id"]
```

## Mermaid: Castrelyx Agent Event Storage

```mermaid
sequenceDiagram
  participant Agent as Castrelyx Agent
  participant Input as TcpMtlsGzipInputAdapter
  participant Dispatcher as ProcessingDispatcher
  participant Db as MariaDbOutputAdapter
  participant Ch as ClickHouseOutputAdapter
  participant Tail as LiveTailService

  Agent->>Input: TLS handshake with client certificate
  Agent->>Input: [frame length][gzip JSON batch]
  Input->>Input: verify certificate CN equals batch source_id
  Input->>Dispatcher: LogEvent per batch item
  Dispatcher->>Tail: WebSocket broadcast
  Dispatcher->>Db: batch insert event_json + indexed columns
  Dispatcher->>Ch: JSONEachRow insert event_json + indexed columns
```

## PlantUML/Class Diagram Sample

```plantuml
@startuml
interface InputAdapter
interface OutputAdapter

class TlsConfigSupport
class TlsTcpInputAdapter
class HttpsInputAdapter
class RabbitMqInputAdapter
class TlsRabbitMqInputAdapter
class TcpMtlsGzipInputAdapter
class MariaDbOutputAdapter
class ClickHouseOutputAdapter

InputAdapter <|.. TlsTcpInputAdapter
InputAdapter <|.. HttpsInputAdapter
InputAdapter <|.. RabbitMqInputAdapter
RabbitMqInputAdapter <|-- TlsRabbitMqInputAdapter
InputAdapter <|.. TcpMtlsGzipInputAdapter

TlsTcpInputAdapter --> TlsConfigSupport
HttpsInputAdapter --> TlsConfigSupport
RabbitMqInputAdapter --> TlsConfigSupport : TLS client context

OutputAdapter <|.. MariaDbOutputAdapter
OutputAdapter <|.. ClickHouseOutputAdapter
@enduml
```
