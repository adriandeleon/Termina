# {{jreleaserCreationStamp}}
# yaml-language-server: $schema=https://aka.ms/winget-manifest.installer.1.9.0.schema.json

# Hand-written rather than JReleaser's stock template, which emitted empty Scope, InstallModes and
# UpgradeBehavior keys — empty is not the same as absent to the schema validator.
#
# An MSI needs no NestedInstallerType or NestedInstallerFiles; those belong to the zip form this
# replaced. ProductCode is the MSI's own, read from the file by release.yml: it is how winget finds
# an installed Termina to upgrade or remove, and jpackage makes a new one for every version.
PackageIdentifier: {{wingetPackageIdentifier}}
PackageVersion: {{wingetPackageVersion}}
MinimumOSVersion: {{wingetMinimumOsVersion}}
InstallerType: msi
Scope: user
UpgradeBehavior: install
ReleaseDate: {{wingetReleaseDate}}
Installers:
  - Architecture: {{wingetInstallerArchitecture}}
    InstallerUrl: {{distributionUrl}}
    InstallerSha256: {{distributionChecksumSha256}}
    ProductCode: '{{wingetProductCode}}'
ManifestType: installer
ManifestVersion: 1.9.0
