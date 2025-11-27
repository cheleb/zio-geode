**Task 1**: Implement Geode configuration data structures and validation
**Description**: Define case classes and sealed traits for Geode client configuration, including locators, authentication credentials, SSL settings, and pool configurations. Include validation logic to ensure configurations are valid before client creation. Use ZIO's configuration module for loading from environment or files.
**Purpose**: To provide a type-safe way to configure Geode clients, ensuring all necessary parameters are present and valid, preventing runtime errors from misconfiguration.
**Behavior**: The module exposes a `GeodeConfig` case class with fields for locators (list of host:port), auth (username/password or none), SSL (enabled/disabled with keystore/truststore paths), and pool settings (min/max connections). It provides a `validate` method that returns ZIO[Nothing, Either[ConfigError, ValidConfig]] where ConfigError is a sealed trait for specific validation failures. Configuration can be loaded from ZIO config sources.
**Requirements**:
* Support for multiple locators for high availability.
* Optional authentication with username/password.
* SSL/TLS support with configurable keystores and truststores.
* Pool configuration for connection management.
* Validation must check locator formats, file paths for SSL, and logical constraints (e.g., min connections <= max).
* Integration with ZIO's config system for loading from HOCON, environment variables, etc.
**Testing criteria**:
* Happy path: Valid config with all fields set correctly validates successfully.
* Edge cases: Invalid locator formats (non-numeric ports, invalid hosts) fail validation; missing required fields fail; SSL enabled without keystore paths fail; pool min > max fails.
* Roundtrip: Config loaded from source, validated, and used to create client without errors.

**Task 2**: Implement Geode client lifecycle management with ZIO layers
**Description**: Create a ZIO layer that manages the lifecycle of Apache Geode's ClientCache, handling creation, configuration, and cleanup. Wrap ClientCache in ZManaged for resource safety.
**Purpose**: To provide a managed Geode client instance that can be injected into ZIO environments, ensuring proper initialization and shutdown.
**Behavior**: Exposes a `GeodeClient` service with access to the underlying ClientCache. The layer takes a ValidConfig and returns ZLayer[Any, GeodeError, GeodeClient], where GeodeError maps Geode exceptions. Client creation connects to locators, applies auth and SSL if configured.
**Requirements**:
* ClientCache created with provided config.
* Proper resource management:
 * ClientCache closed on layer shutdown, but not thread safe (only one instance on ClientCache is allowed per JVM).
 * ClientCache is created only once per JVM.
* Error handling: Map Geode exceptions (e.g., AuthenticationFailedException) to custom GeodeError types.
* Thread-safe and suitable for concurrent access.
* No direct exposure of ClientCache; all operations through the service.
**Testing criteria**:
* Happy path: Layer creates client successfully with valid config, client is usable for operations.
* Edge cases: Invalid locators cause connection failures mapped to GeodeError; auth failures; SSL handshake failures; layer shutdown closes client properly.
* Concurrent access: Multiple fibers accessing client simultaneously without issues.

**Task 3**: Implement region management operations
**Description**: Provide operations to create, access, and destroy Geode regions, supporting different region types (e.g., PARTITIONED, REPLICATED). Wrap region creation in ZIO effects.
**Purpose**: To enable creation and management of data regions, which are the primary storage units in Geode.
**Behavior**: GeodeClient service includes methods like `createRegion(name: String, regionType: RegionType): ZIO[GeodeClient, GeodeError, Region[K,V]]`, `getRegion(name: String): ZIO[GeodeClient, GeodeError, Option[Region[K,V]]]`, `destroyRegion(name: String): ZIO[GeodeClient, GeodeError, Unit]`. RegionType is a sealed trait for PARTITIONED, REPLICATED, etc.
**Requirements**:
* Support for partitioned and replicated regions.
* Type-safe region access with K,V parameters.
* Regions are created on demand and cached in the client.
* Destroy removes region from cache and Geode.
* Error handling for region already exists, not found, etc.
**Testing criteria**:
* Happy path: Create region, access it, perform operations, destroy successfully.
* Edge cases: Create region that already exists fails; get non-existent region returns None; destroy non-existent region fails; concurrent region creation.
* Type safety: Regions enforce key/value types.

**Task 4**: Implement basic CRUD data operations
**Description**: Wrap Geode's put, get, remove operations on regions in ZIO effects, handling asynchronous execution and errors.
**Purpose**: To provide fundamental data access operations for storing and retrieving entries in Geode regions.
**Behavior**: Region service (or extension) includes `put(key: K, value: V): ZIO[Region[K,V], GeodeError, Unit]`, `get(key: K): ZIO[Region[K,V], GeodeError, Option[V]]`, `remove(key: K): ZIO[Region[K,V], GeodeError, Boolean]` (true if removed). Operations are asynchronous via ZIO.
**Requirements**:
* Asynchronous execution using ZIO's async capabilities.
* Handle null values appropriately (Geode allows nulls).
* Error handling for region not found, serialization errors, etc.
* Thread-safe for concurrent operations on the same region.
* Support for bulk operations if needed, but start with single entry.
**Testing criteria**:
* Happy path: Put value, get returns it; remove returns true, subsequent get None.
* Edge cases: Put/get/remove on non-existent region fails; put null value; get non-existent key returns None; concurrent puts/gets.
* Serialization: Custom objects serialize/deserialize correctly.

**Task 5**: Implement OQL query execution
**Description**: Provide functionality to execute Object Query Language (OQL) queries on Geode regions, returning results as ZIO streams or collections.
**Purpose**: To enable querying data in regions using Geode's query language for complex data retrieval.
**Behavior**: GeodeClient includes `executeQuery[T](query: String, params: Any*): ZIO[GeodeClient, GeodeError, ZStream[Any, GeodeError, T]]` for streaming results, or `executeQueryCollect[T](query: String, params: Any*): ZIO[GeodeClient, GeodeError, Chunk[T]]` for batched. Supports parameterized queries.
**Requirements**:
* Streaming results for large datasets to avoid memory issues.
* Parameter binding for secure queries.
* Type-safe result extraction (e.g., via implicit converters).
* Error handling for invalid queries, region not found, etc.
* Pagination support for large result sets.
**Testing criteria**:
* Happy path: Simple query returns correct results; parameterized query with params.
* Edge cases: Invalid OQL syntax fails; query on non-existent region fails; empty results; large result sets streamed correctly.
* Performance: Streaming handles large datasets without OOM.

**Task 6**: Implement continuous query streaming
**Description**: Integrate Geode's continuous queries (CQ) with ZIO streams for real-time data change notifications.
**Purpose**: To support event-driven programming where data changes in regions trigger computations in ZIO.
**Behavior**: GeodeClient provides `continuousQuery[T](query: String, params: Any*): ZIO[GeodeClient, GeodeError, ZStream[Scope, GeodeError, CqEvent[T]]]` where CqEvent represents create/update/destroy events with data. Stream runs until managed resource is released.
**Requirements**:
* CQ registered and unregistered properly via ZManaged.
* Events streamed as they occur.
* Support for parameterized CQs.
* Error handling for CQ failures.
* Thread-safe event processing.
**Testing criteria**:
* Happy path: CQ registered, data changes trigger events in stream.
* Edge cases: Invalid CQ query fails; region changes during CQ; concurrent CQs.
* Lifecycle: CQ unregistered on stream close.

**Task 7**: Implement transaction management
**Description**: Wrap Geode's transaction API in ZIO's transactional effects for atomic operations across regions.
**Purpose**: To ensure consistency in multi-operation workflows by grouping them into transactions.
**Behavior**: Provide `transactional[R, E, A](zio: ZIO[R, E, A]): ZIO[R & GeodeClient, E | GeodeError, A]` that runs the ZIO in a Geode transaction, committing on success, rolling back on failure. Also direct transaction control if needed.
**Requirements**:
* Automatic commit/rollback based on ZIO outcome.
* Support for nested transactions if possible.
* Error handling for transaction conflicts, timeouts.
* Integration with ZIO's error model.
**Testing criteria**:
* Happy path: Multiple operations in transaction commit successfully.
* Edge cases: Failure in transaction rolls back; concurrent transactions; transaction timeouts.
* Consistency: Changes visible only after commit.

**Task 8**: Implement region event handling
**Description**: Provide listeners for region events like entry creation, update, destruction, integrated with ZIO's event system.
**Purpose**: To react to data changes in regions for caching invalidation, logging, etc.
**Behavior**: Region can register listeners: `onEntryEvent(handler: EntryEvent[K,V] => ZIO[Any, Nothing, Unit]): ZManaged[Region[K,V], GeodeError, Unit]`. Events processed asynchronously.
**Requirements**:
* Support for create, update, destroy events.
* Asynchronous event handling without blocking.
* Proper listener registration/unregistration.
* Error handling in handlers (logged, not propagated).
**Testing criteria**:
* Happy path: Listener registered, events trigger handler.
* Edge cases: Multiple listeners; handler errors; region destroyed.
* Concurrency: Events from concurrent operations.

**Task 9**: Implement error handling and recovery
**Description**: Define a comprehensive error hierarchy mapping Geode exceptions to ZIO failures, with retry mechanisms for transient errors.
**Purpose**: To provide robust error handling and automatic recovery for distributed operations.
**Behavior**: GeodeError sealed trait with cases for connection errors, auth failures, query errors, etc. Provide retry policies: `withRetry[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A]` that retries on transient errors.
**Requirements**:
* Exhaustive mapping of Geode exceptions.
* Retry for network issues, node unavailability.
* Configurable retry policies (backoff, max attempts).
* Logging of errors.
**Testing criteria**:
* Happy path: Errors mapped correctly.
* Edge cases: Retry succeeds after transient failure; non-retryable errors fail immediately.
* Recovery: System recovers from failures.

**Task 10**: Implement monitoring and metrics
**Description**: Expose Geode metrics (operation counts, latencies) via ZIO's metrics system for observability.
**Purpose**: To provide insights into performance and health of Geode operations.
**Behavior**: GeodeClient includes methods to access metrics: `operationCount: ZIO[GeodeClient, Nothing, Long]`, etc. Metrics updated automatically on operations.
**Requirements**:
* Counters for puts, gets, queries.
* Histograms for latencies.
* Integration with ZIO metrics.
* No performance impact on operations.
**Testing criteria**:
* Happy path: Metrics updated correctly after operations.
* Edge cases: Metrics reset; concurrent updates.
* Accuracy: Metrics reflect actual operations.