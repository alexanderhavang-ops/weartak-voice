# Contributing

Keep this repository focused on the standalone watch app. Put Martine, TAKS onboarding and server
changes in `alexanderhavang-ops/taks`; put WearTAK host and Companion changes in their own
repositories or forks. Document cross-repository requirements without claiming unmerged work
is available to users.

For each behaviour change:

1. Add or update a meaningful test and run the relevant checks.
2. Update [status](docs/STATUS.md) with actual verification and remaining limitations.
3. Update [architecture/contracts](docs/ARCHITECTURE.md) when interfaces change.
4. Update the [WearTAK team brief](docs/WEARTAK_TEAM.md) when maintainer-facing requirements change.
5. Update README examples if build, setup or operator behaviour changes.

Do not commit credentials, private certificates, signing keys, real operator packages, device
telemetry or generated build output. Test fixtures must remain synthetic and include source
provenance. No automatic deployment or GitHub Actions pipeline is required.
