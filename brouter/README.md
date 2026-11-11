# BRouter (vendored)

Offline routing engine used by MapAround's route planner.

- Upstream: https://github.com/abrensch/brouter, tag `v1.7.10`
- Licence: MIT (see `LICENSE`)
- Contents: the unmodified `src/main/java` of `brouter-util`, `brouter-codec`, `brouter-expressions`,
  `brouter-mapaccess` and `brouter-core`. The routing profiles used by the app (`lookups.dat` and `*.brf`)
  are in `app/src/main/assets/brouter/profiles2/`.

To update, replace `src/main/java` with the same modules from a newer tag and refresh the profiles.
