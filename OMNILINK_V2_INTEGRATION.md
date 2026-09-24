# OmniLink v2 — OmniDev / AndroidIDE integration

This change targets OmniDev `feature/omnilink-v1.2-live-history` and AndroidIDE `dev`.
It does not publish a new SDK release or silently merge OmniDev PR #90 into `main`.

## Build and distribution

- Trusted Android artifact: `com.github.obieda-hussien.OmniLinkSDK:omni-link-sdk:v2.0.1`.
- A public third-party app should use `omni-link-public` only.
- CI stages the real Android and JVM module artifacts; the aggregate is POM-only.
- A downloaded/copied trusted AAR **never** grants first-party identity. Final installed APK
  signing identity and the receiving service's own ACL decide authority.
- Private Maven distribution for the trusted AAR is a separate future supply-chain step; do not
  confuse it with runtime security. No secret/signing private key is embedded in source.

## Android identity and flavor policy

Workspace owns the custom signature permissions. AndroidIDE refuses a provider that lacks the
expected service permission or real matching signer and binds explicitly after verification.

Admin uses `com.omnidev.workspace.admin` so its actual Android package + signer can be
distinguished from Lite `com.omnidev.workspace`. This is a **separate installation/data sandbox**;
an older Admin build using the base package will not automatically migrate its local database.

IDE's receiver enforces the policy even if a caller skips the OmniDev tool UI:

| Caller (verified same signer) | IDE access |
|---|---|
| Admin (.admin) | IDE's declared `ide.*` capabilities, subject to permissions, capability risk and required confirmation |
| Pro/OEM | Read/search/diagnostics plus non-destructive build/test/sync |
| Norm | Health/context + focused read/search |
| Lite (base package) | Health/project overview |
| Unknown/other | No privileged IDE Binder access |

The caller-side OmniDev tier check is an additional guard, not a substitute for IDE's receiver
authorization. Root/Shizuku/Accessibility are independently permission-gated by Android and the
existing OmniDev TierPolicy; the SDK is not an OS privilege escalation.

## Shared memory

AndroidIDE retains ownership of live editor buffers/project state. OmniDev owns its own Room DB and
receives *bounded, versioned deltas*, not another application's database file.

- `workspace.context.publish`: project root, state, active path/revision/dirty flag and a short
  build tail; **not** entire source buffers.
- `workspace.memory.upsert/search/delta/recent`: caller-provenanced record IDs, bounded pages.
- `workspace.diagnostics.publish`: structured diagnostic context, not a privileged command.
- `search_knowledge` includes matching shared records and labels them untrusted connected-app
  data; they must not be executed as instructions.
- Same-signer first-party eligibility is necessary, not automatically sufficient for sensitive
  capabilities, and public/unknown applications have no access to the privileged memory service.

## Large payloads

`ide.export_payload` is Admin-only and returns a time-limited grant-scoped Content URI, byte size
and SHA-256. It does **not** return file bytes through Binder. Workspace's Admin
`omni_link(receive_payload)` verifies provider signer, authority, quota and checksum, and streams
the file into its private inbox through `AndroidPayloadBroker`.

The Content URI path contains an opaque token rather than a project path. AndroidIDE enforces
read-only access and the exact approved package/UID. Both sides use background I/O.

The SDK also provides encrypted resumable file transfer for **desktop TCP/LAN/ADB**, but this PR
does not install an always-on desktop server or assert successful Android↔PC end-to-end testing.
Same-device URI transfer must be retried with a new grant after source changes/process death;
resume is a feature of the SDK's encrypted network data plane, not of this URI provider.

## Acceptance checks

1. `Gradle v2` module artifact staging succeeds, including JVM transport transitive dependency.
2. Same-signed Admin can invoke IDE writes; Lite/Norm/Pro/OEM mutation attempts are denied in the
   IDE service itself.
3. An unsigned APK copying OmniLink, package names, Intent actions or a manifest cannot bind to
   privileged services or obtain payload grants.
4. Memory's Room 16→17 migration retains existing chats and learned data.
5. Bounded project-context publish/search works after both apps install with shared signer.
6. Large-file descriptor transfer preserves byte length and SHA-256; malformed/expired grants fail
   without crashing either app.
7. Both apps' CI compile/test/security checks pass before merging the cross-repository PRs.
