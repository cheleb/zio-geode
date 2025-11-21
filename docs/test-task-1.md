# Task 1: Geode Configuration Validation Tests

This document outlines the detailed test cases for Task 1: Implement Geode configuration data structures and validation.

## Test Cases

### Happy Path
- **Test Case: Valid config with all fields**
  - **Input**: GeodeConfig with valid locators, auth, SSL enabled with paths, valid pool settings
  - **Expected**: Validation succeeds, returns ValidConfig
  - **Coverage**: Ensures complete valid configurations are accepted

### Edge Cases - Locator Validation
- **Test Case: Empty host in locator**
  - **Input**: Locator("", 10334)
  - **Expected**: InvalidLocatorFormat error
  - **Coverage**: Host must be non-empty

- **Test Case: Invalid port (negative)**
  - **Input**: Locator("localhost", -1)
  - **Expected**: InvalidLocatorFormat error
  - **Coverage**: Port must be between 1 and 65535

- **Test Case: Invalid port (too high)**
  - **Input**: Locator("localhost", 70000)
  - **Expected**: InvalidLocatorFormat error
  - **Coverage**: Port must be <= 65535

- **Test Case: Invalid host format**
  - **Input**: Locator("invalid..host", 10334)
  - **Expected**: InvalidLocatorFormat error (if validation includes host format)
  - **Coverage**: Basic host format validation

### Edge Cases - Required Fields
- **Test Case: Empty locators list**
  - **Input**: locators = Nil
  - **Expected**: MissingRequiredFields error
  - **Coverage**: At least one locator required

### Edge Cases - SSL Configuration
- **Test Case: SSL enabled without keystore path**
  - **Input**: ssl.enabled = true, keystorePath = None
  - **Expected**: SslEnabledWithoutKeystore error
  - **Coverage**: SSL requires keystore

- **Test Case: SSL enabled without truststore path**
  - **Input**: ssl.enabled = true, truststorePath = None
  - **Expected**: SslEnabledWithoutKeystore error
  - **Coverage**: SSL requires truststore

- **Test Case: SSL enabled with non-existent keystore file**
  - **Input**: ssl.enabled = true, keystorePath = "/nonexistent"
  - **Expected**: SslEnabledWithoutKeystore error
  - **Coverage**: File existence check for SSL paths

### Edge Cases - Pool Configuration
- **Test Case: Min connections > max connections**
  - **Input**: Pool(minConnections = 10, maxConnections = 5)
  - **Expected**: PoolConstraintsViolated error
  - **Coverage**: Logical constraint validation

- **Test Case: Negative min connections**
  - **Input**: Pool(minConnections = -1, maxConnections = 10)
  - **Expected**: PoolConstraintsViolated error (if validated)
  - **Coverage**: Positive values required

### Roundtrip Test
- **Test Case: Config loaded from source, validated, and used**
  - **Input**: Config loaded via ZIO config from HOCON string
  - **Expected**: Validation succeeds, ValidConfig can be extracted
  - **Coverage**: Integration with ZIO config system

## Test Implementation Notes

- Tests use ZIO Test framework with ZIOSpecDefault
- Assertions use zio.test.Assertion._ for Either results
- Mock file existence for SSL path validation
- Test both success and failure cases for each validation rule
- Ensure error messages are specific and actionable

## Coverage Goals

- All validation branches covered
- Both valid and invalid inputs tested
- Edge cases for boundary values
- Integration with config loading
- Error specificity verified