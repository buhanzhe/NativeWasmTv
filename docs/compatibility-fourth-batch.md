# Android 4.4 fourth compatibility batch

18 previously untested representative domains cover 137 listed entries, not 137 verified pages. Ten entry pages and 118 of 143 direct script/style dependencies were captured. Eight entry URLs were denied or unavailable. Missing requests remain failures. Captured site source is not redistributed.

## Narrow verified repairs

- Harbin: exact versioned script URL, page scope and SHA-256 identify a 972-byte navigation script mistakenly served with HTML entities. The response adapter decodes only its four known entities after the existing HTTP/MIME/size checks. A jQuery 3-only `.size()` alias is scoped to the same verified page. API19 captured-page replay reduced two JavaScript errors to zero while retaining 1,536 body-text characters. Forty-nine uncaptured resources remain; this is not live-site acceptance.
- Xi’an: the exact captured desktop base script installs a text-cleanup observer. API19 exposed an absent-body observer binding during navigation to its real mobile URL. Exact source/page/URL gates add two null-body lifecycle guards. Normal text cleanup, observer options and real errors remain intact. This avoids the observed teardown failures; it does not add late-body recovery if a body first appears after DOMContentLoaded.

Both native source repairs follow the existing optional transpilation path on API19. Transpilation remains OFF by default, its 512 KiB input limit and 8-second document-budget accounting are unchanged, and these fixes do not bypass denial, authentication, TLS or missing upstream data. Cold compiler initialization can make a successful individual compilation exceed 8 seconds wall time; the existing document budget then stops later compilation. A captured/offline test is not an end-to-end live network result.

## Regression commands

- `node tools/web-compat/test-page-adapters.cjs`
- `JAVA_HOME=/path/to/jdk python tools/web-compat/test-script-source-adapter.py`
- `JAVA_HOME=/path/to/jdk python tools/web-compat/test-xian-source-adapter.py`

The Python tests use authored synthetic fixtures and temporary compiled Java classes. An optional local official-source input may be supplied to verify the production fingerprint; such inputs and adapted outputs must not be included in distribution archives.
