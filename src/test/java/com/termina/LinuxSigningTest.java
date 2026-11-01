package com.termina;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Linux signing chain (README, "Linux signing"; packaging/linux/NOTES.md). It runs only in a
 * release, and each mistake it guards against ships quietly: a publish that does not wait for the signed
 * files, a tag that releases unsigned packages, or a signature that is made but never attached.
 */
class LinuxSigningTest {

    private static final String[] FORMATS = {"deb", "rpm", "AppImage", "tar.gz"};

    @Test
    void aTagCannotPublishUnsignedLinuxPackages() throws IOException {
        String release =
                Files.readString(Path.of(".github", "workflows", "release.yml")).replace("\r\n", "\n");
        int job = release.indexOf("\n  sign-linux:\n");
        int publish = release.indexOf("\n  release:\n");
        assertTrue(job >= 0 && job < publish, "the release no longer signs the Linux packages");
        String sign = release.substring(job, publish);
        assertTrue(
                sign.contains("GITHUB_REF_TYPE\" = \"tag\"") && sign.contains("exit 1"),
                "a tag without the signing key must fail");
        assertTrue(
                sign.contains("TERMINA_GPG_EXPECTED_KEY=packaging/linux/signing-key.asc"),
                "a tag must be signed by the committed key and no other");
        assertTrue(
                sign.indexOf("sign-linux-packages.sh") < sign.indexOf("check-linux-signatures.sh"),
                "what was signed is checked against the public key before it is uploaded");
        for (String target : new String[] {"linux-x64", "linux-arm64"}) {
            assertTrue(
                    sign.contains("name: Termina-" + target + "\n          path: linux/Termina-" + target
                            + "/*\n          overwrite: true"),
                    "the signed " + target + " files must replace the unsigned ones");
        }
        assertTrue(
                release.substring(publish).contains("needs: [build, sign-linux]"),
                "the publish job must wait for the signed files");
    }

    @Test
    void everyLinuxDownloadIsAttachedWithItsSignature() throws IOException {
        String jreleaser = Files.readString(Path.of("jreleaser.yml"));
        for (String target : new String[] {"linux-x64", "linux-arm64"}) {
            for (String format : FORMATS) {
                String file = "artifacts/Termina-{{projectVersion}}-" + target + "." + format;
                assertTrue(jreleaser.contains("'" + file + "'"), file + " is not attached");
                assertTrue(jreleaser.contains("'" + file + ".asc'"), file + " is attached without its signature");
            }
        }
        assertTrue(jreleaser.contains("'artifacts/termina-signing-key.asc'"), "the public key is not attached");
    }

    @Test
    void theSigningScriptSignsEveryFormatItsCheckExpects() throws IOException {
        String sign = Files.readString(Path.of("scripts", "sign-linux-packages.sh"));
        String check = Files.readString(Path.of("scripts", "check-linux-signatures.sh"));
        for (String format : FORMATS) {
            assertTrue(sign.contains("\"$dir\"/*." + format), "sign-linux-packages.sh does not sign ." + format);
            assertTrue(check.contains("\"$dir\"/*." + format), "check-linux-signatures.sh does not check ." + format);
        }
        assertTrue(
                sign.indexOf("rpmsign") < sign.indexOf("--detach-sign")
                        && sign.indexOf("debsigs") < sign.indexOf("--detach-sign"),
                "the embedded signatures change the files, so the detached ones come after");
    }
}
