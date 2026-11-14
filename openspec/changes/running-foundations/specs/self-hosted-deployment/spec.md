# Spec Delta

## Purpose

Makes the web app and its backend easy to run privately on a home server such as a Raspberry Pi, with data kept on the owner's hardware and no account or sign-in to manage.

## ADDED Requirements

### Requirement: Single-container deployment
The system SHALL be distributed as one container image, available for ARM64 and AMD64, that serves both the API and the web app on a single port and needs no other service to run.

#### Scenario: Start on a Raspberry Pi
- **WHEN** the owner starts the provided compose file on a 64-bit Raspberry Pi with Docker
- **THEN** the web app is reachable on the configured port without installing anything else

### Requirement: Persistent data volume
The system SHALL keep all persistent state — database and retained original files — under one data directory intended to be mounted as a volume, so that upgrading or recreating the container loses nothing and backing up that directory backs up everything.

#### Scenario: Container recreated
- **WHEN** the container is removed and started again with the same data volume
- **THEN** all activities, journal entries, profile settings and original files are still present

#### Scenario: Upgrade with schema change
- **WHEN** a newer image version is started on data created by an older version
- **THEN** the data is migrated automatically at startup and remains accessible

### Requirement: Modest resource footprint
The system SHALL run within 128 MB of memory when idle on a Raspberry Pi 4 class device and SHALL import a one-hour activity file in under 5 seconds on that hardware.

#### Scenario: Idle footprint
- **WHEN** the container has been running idle for ten minutes with 500 stored activities
- **THEN** its memory usage is below 128 MB

### Requirement: Open access on a trusted network
The system SHALL NOT require any sign-in, password or token: every client able to reach the service SHALL have full access to all profiles and features. The deployment documentation SHALL state that the service must only be exposed on a trusted network.

#### Scenario: Open the app
- **WHEN** someone on the home network opens the app
- **THEN** it is usable immediately with no sign-in screen

#### Scenario: Non-browser client
- **WHEN** a client on the home network uploads an activity file for a profile without presenting any credential
- **THEN** the upload is accepted

### Requirement: Cross-site request protection
Because access is open, the system SHALL refuse state-changing requests that originate from web pages served by another site, so that a page visited on the internet cannot modify or delete data through the user's browser.

#### Scenario: Forged request from another site
- **WHEN** a page from another origin makes the browser send a delete request to the service
- **THEN** the request is refused and no data changes

### Requirement: No third-party data sharing
The system SHALL NOT send activity data, positions or profile data to any external service. The only permitted outbound requests are map background tiles fetched by the browser from the configured tile source, which SHALL be replaceable by a self-hosted source or disabled.

#### Scenario: Default operation
- **WHEN** the owner imports and views activities
- **THEN** the server makes no outbound network request

#### Scenario: Map background disabled
- **WHEN** the owner disables the map tile source
- **THEN** the browser makes no request to any host other than the server
- **AND** traces are drawn on a plain background

### Requirement: Health check
The system SHALL expose a health endpoint reporting whether the service and its data store are operational, without disclosing any user data.

#### Scenario: Healthy service
- **WHEN** the health endpoint is queried on a running instance
- **THEN** it reports a healthy status and the application version

### Requirement: Configuration by environment
The system SHALL be configurable through environment variables for listening port, data directory, maximum upload size and map tile source, with working defaults for all of them.

#### Scenario: No configuration
- **WHEN** the container is started with no environment variables set
- **THEN** it starts successfully using the documented defaults
