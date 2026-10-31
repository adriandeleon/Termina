package com.termina;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the macOS Developer ID signing chain (README, "macOS signing"). None of it runs off a Mac or
 * outside a release, and each mistake it guards against ships quietly: an app signed before a later step
 * edits it, a dmg wrap that re-signs it ad hoc, or a tag that publishes an unsigned dmg.
 */
class MacSigningTest {

    @Test
    void theAppIsSignedAfterItsLastEditAndBeforeTheInstallersWrapIt() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        int sign = pom.indexOf("<id>sign-mac-app</id>");
        assertTrue(sign >= 0, "the dist profile no longer signs the app");
        for (String edit : new String[] {"add-notices", "aot-train", "fix-mac-bundle-version"}) {
            int at = pom.indexOf("<id>" + edit + "</id>");
            assertTrue(at >= 0 && at < sign, edit + " changes the bundle, so it must run before signing");
        }
        assertTrue(sign < pom.indexOf("<id>build-installers</id>"), "the dmg must wrap the signed app");
    }

    @Test
    void theSigningScriptKeepsItsSignatureThroughTheDmgWrap() throws IOException {
        String script = Files.readString(Path.of("scripts", "sign-mac-app.sh"));
        assertTrue(script.contains("<signed>true</signed>"), "jpackage re-signs an image it believes unsigned, ad hoc");
        assertTrue(script.indexOf(".jpackage.xml") < script.indexOf("xattr -cr"), "the flag is sealed: set it first");
        assertTrue(
                script.contains("--options runtime") && script.contains("--timestamp"),
                "notarization requires the hardened runtime and a secure timestamp");
        assertTrue(
                Files.readString(Path.of("packaging", "macos", "Termina.entitlements"))
                        .contains("com.apple.security.cs.disable-library-validation"),
                "JNA and pty4j load natives they unpacked, which no team signed");
        assertTrue(
                Files.readString(Path.of("scripts", "build-installers.sh")).contains("TERMINA_MAC_SIGN_IDENTITY"),
                "the dmg gets its own signature");
    }

    @Test
    void aTagCannotPublishAnUnsignedOrUnnotarizedDmg() throws IOException {
        String release =
                Files.readString(Path.of(".github", "workflows", "release.yml")).replace("\r\n", "\n");
        int importCert = release.indexOf("- name: Import signing certificate (macOS)");
        int build = release.indexOf("- name: Build, test and package");
        int smoke = release.indexOf("- name: Smoke-test the packaged app");
        int notarize = release.indexOf("- name: Notarize the dmg (macOS)");
        int upload = release.indexOf("- name: Upload\n");
        assertTrue(importCert >= 0 && notarize >= 0, "the macOS legs no longer sign and notarize");
        assertTrue(
                importCert < build && build < smoke && smoke < notarize && notarize < upload,
                "the keychain exists before the build signs, the smoke test runs the signed app, and the dmg is"
                        + " notarized before it is uploaded");
        String importStep = release.substring(importCert, build);
        assertTrue(
                importStep.contains("GITHUB_REF_TYPE\" = \"tag\"") && importStep.contains("exit 1"),
                "a tag without the signing secrets must fail");
        String notarizeStep = release.substring(notarize, upload);
        assertTrue(notarizeStep.contains("stapler staple") && notarizeStep.contains("spctl --assess"));
        assertTrue(
                release.contains("if: always() && steps.macsign.outputs.enabled == 'true'")
                        && release.contains("security delete-keychain"),
                "the keychain holding the private key is removed even when the leg fails");
    }
}
