# Release signing

`ceres-release.jks` is the **stable** release keystore for GitHub Actions and local
`assembleRelease` builds. All public APKs from this repo should use this key so
updates can overwrite each other.

Passwords (also in `app/build.gradle.kts`):

- store / key password: `ceres-release`
- alias: `ceres`

This key is public on purpose (same model as shipping debug-signed builds before).
Do **not** reuse it for Play Store production if you later need a private key.

## Migrating from older debug-signed installs

APKs before the fixed keystore (including CI debug-signed `0.1.4`) cannot be
updated in place. Uninstall the old package once, then install the new release;
later versions upgrade normally.
