# {{jreleaserCreationStamp}}
{{#brewRequireRelative}}
require_relative "{{.}}"
{{/brewRequireRelative}}

cask "{{brewCaskName}}" do
  desc "{{projectDescription}}"
  homepage "{{projectLinkHomepage}}"
  url "{{distributionUrl}}"{{#brewDownloadStrategy}}, :using => {{.}}{{/brewDownloadStrategy}},
      verified: "{{repoHost}}"
  version "{{projectVersion}}"
  sha256 "{{distributionChecksumSha256}}"
  name "{{brewCaskDisplayName}}"
  {{#brewCaskHasAppcast}}
  appcast {{brewCaskAppcast}}
  {{/brewCaskHasAppcast}}
  auto_updates true

  {{! Written out here rather than through extraProperties, which did not reach the template. The
      app is ad-hoc signed, not signed with a Developer ID and not notarised, so a downloaded copy
      is refused by Gatekeeper on first launch — verified with spctl, which reports "rejected". }}
  caveats <<~EOS
    Termina is not signed with an Apple Developer ID yet, so macOS will refuse to open it the
    first time. Allow it once under System Settings > Privacy & Security > Open Anyway.
  EOS

  {{#brewHasLivecheck}}
  livecheck do
    {{#brewLivecheck}}
    {{.}}
    {{/brewLivecheck}}
  end
  {{/brewHasLivecheck}}
  {{#brewDependencies}}
  depends_on {{.}}
  {{/brewDependencies}}

  {{#brewCaskHasPkg}}
  pkg "{{brewCaskPkg}}"
  {{/brewCaskHasPkg}}
  {{#brewCaskHasApp}}
  app "{{brewCaskApp}}"
  {{/brewCaskHasApp}}
  {{! The stock template emits a `binary` stanza pointing at <root>/bin/<executable>, which is the
      layout of a command-line tool unpacked into a folder. What is shipped here is a macOS
      application bundle: the executable lives at Termina.app/Contents/MacOS/Termina, and what a
      cask should do with a bundle is install it, not symlink into it. The cask.appName setting is
      meant to produce exactly this stanza but is dropped from the resolved configuration, which is
      why the template says it outright rather than going through the option. }}
  app "Termina.app"
  {{#brewCaskHasUninstall}}
  {{#brewCaskUninstall}}
  uninstall {{name}}: [
    {{#items}}
    "{{.}}",
    {{/items}}
  ]
  {{/brewCaskUninstall}}
  {{/brewCaskHasUninstall}}
  {{#brewCaskHasZap}}
  {{#brewCaskZap}}
  zap {{name}}: [
    {{#items}}
    "{{.}}",
    {{/items}}
  ]
  {{/brewCaskZap}}
  {{/brewCaskHasZap}}
end
