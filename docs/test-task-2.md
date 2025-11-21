# Task 2: Geode Client Lifecycle Management Tests

This document outlines the detailed test cases for Task 2: Implement Geode client lifecycle management with ZIO layers.

## Test Cases

### Happy Path
- **Test Case: Layer creates client successfully with valid config**
  - **Input**: ValidConfig with proper locators, auth, SSL, pool settings
  - **Expected**: Layer provides GeodeClient, client connects to Geode, operations succeed
  - **Coverage**: Ensures client creation and basic usability

### Edge Cases - Connection Failures
- **Test Case: Invalid locators cause connection failures**
  - **Input**: ValidConfig with non-existent locators (e.g., invalid host/port)
  - **Expected**: Layer fails with GeodeError.ConnectionError
  - **Coverage**: Connection failures are properly mapped

- **Test Case: Authentication failures**
  - **Input**: ValidConfig with invalid username/password
  - **Expected**: Layer fails with GeodeError.AuthenticationFailed
  - **Coverage**: Auth failures mapped to specific error

- **Test Case: SSL handshake failures**
  - **Input**: ValidConfig with SSL enabled but invalid certificates
  - **Expected**: Layer fails with GeodeError.SslError
  - **Coverage**: SSL issues handled correctly

### Edge Cases - Lifecycle Management
- **Test Case: Layer shutdown closes client properly**
  - **Input**: Layer created and used, then environment shut down
  - **Expected**: ClientCache is closed, no resource leaks
  - **Coverage**: Proper cleanup on shutdown

### Concurrent Access
- **Test Case: Multiple fibers access client simultaneously**
  - **Input**: Multiple concurrent ZIO fibers using the GeodeClient
  - **Expected**: All operations succeed without interference
  - **Coverage**: Thread-safety and concurrent access

## Test Implementation Notes

- Tests use ZIO Test framework with ZIOSpecDefault
- Use test containers or mocks for Geode instances to avoid real dependencies
- Assertions verify layer construction, error mapping, and resource cleanup
- Test both success and failure scenarios
- Ensure error types are specific and actionable
- Mock ClientCache for unit tests, use test containers for integration

## Coverage Goals

- All error mapping scenarios covered
- Lifecycle management (creation/cleanup) tested
- Concurrent access verified
- Integration with ZIO layers confirmed
- Error specificity and handling validated