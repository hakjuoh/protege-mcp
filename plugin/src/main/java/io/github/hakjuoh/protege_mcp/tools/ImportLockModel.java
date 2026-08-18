package io.github.hakjuoh.protege_mcp.tools;

import java.nio.file.Path;

/** Neutral data contracts and policy labels shared by import-lock collaborators. */
final class ImportLockModel {
    private ImportLockModel() {}

    static final long WRITE_HOP_TIMEOUT_MS = ChangeSetTools.COMMIT_TIMEOUT_MS;
    static final String SOURCE_POLICY_DECLARED = "policy_declared";
    static final String SOURCE_BESIDE_DOCUMENT = "beside_document";
    static final String BESIDE_DOCUMENT_TRUST_NOTE =
            "The lockfile is not policy-pinned: it lives beside (and is writable with) the ontology"
                + " folder, so this verification attests accident-safety (content unchanged since"
                + " lock time), not tamper-evidence.";

    record GateLock(Path path, String source) {}

    record ParsedLockedDocument(String memberKey, String memberLine, boolean sessionOnly) {}

    record LockEntry(
            String ontologyIri,
            String versionIri,
            String document,
            String sha256,
            boolean direct,
            Path absolute) {
        String key() {
            return versionIri == null || versionIri.isBlank()
                    ? ontologyIri
                    : ontologyIri + " @ " + versionIri;
        }
    }
}
