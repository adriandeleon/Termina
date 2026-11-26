# {{jreleaserCreationStamp}}
{{! Overrides JReleaser's stock cask template; why is in src/jreleaser/brew-cask-notes.md. }}
cask "{{brewCaskName}}" do
  desc "{{projectDescription}}"
  homepage "{{projectLinkHomepage}}"
  url "{{distributionUrl}}",
      verified: "{{repoHost}}"
  version "{{projectVersion}}"
  sha256 "{{distributionChecksumSha256}}"
  name "{{brewCaskDisplayName}}"

  depends_on arch: :arm64

  app "Termina.app"

  zap trash: [
    "~/.termina",
  ]
end
