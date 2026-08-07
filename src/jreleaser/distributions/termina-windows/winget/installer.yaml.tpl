# {{jreleaserCreationStamp}}
# yaml-language-server: $schema=https://aka.ms/winget-manifest.installer.1.9.0.schema.json

# Hand-written rather than JReleaser's stock template, which produced a manifest winget would
# reject: empty Scope, InstallModes and UpgradeBehavior keys, and — the one that matters — no
# NestedInstallerType/NestedInstallerFiles, which a zip installer is required to carry. An empty
# key is not the same as an absent one to the schema validator.
#
# The relative path is the jpackage app-image layout: the zip contains a Termina/ folder with the
# launcher at its root, beside app/ and runtime/. It is written literally because it is a property
# of how jpackage lays an image out, not of anything JReleaser knows.
PackageIdentifier: {{wingetPackageIdentifier}}
PackageVersion: {{wingetPackageVersion}}
MinimumOSVersion: {{wingetMinimumOsVersion}}
InstallerType: zip
NestedInstallerType: portable
NestedInstallerFiles:
  - RelativeFilePath: Termina\Termina.exe
    PortableCommandAlias: termina
UpgradeBehavior: install
ReleaseDate: {{wingetReleaseDate}}
Installers:
  - Architecture: {{wingetInstallerArchitecture}}
    InstallerUrl: {{distributionUrl}}
    InstallerSha256: {{distributionChecksumSha256}}
ManifestType: installer
ManifestVersion: 1.9.0
