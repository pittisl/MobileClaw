# Release notes

## 0.1.1 — MobiCom 2026 final demonstration update

- Reply-capable notification conversations now have separate five-minute follow-up
  windows. Admitted conversations queue behind the active turn; group summaries
  are excluded from triage, and missing reply fields cannot create a reply proposal.
- Notification replies backed by activities opt into Android's sender launch
  privileges, using visible-sender scope on Android 16+. Results report dispatch
  to the target action rather than a carrier delivery receipt.
- Optional Digital Wellbeing and Quick Share AppFunctions queries return readable
  state summaries. Reads retain explicit confirmation and preserve unavailable
  values, access restrictions and explanatory hints.
- The Windows desktop trace launcher discovers Python/ADB, selects among connected
  phones and keeps its console available for errors and live output.
- Demo regressions and reproducible system-query instructions accompany these
  updates. The README now records the completed MobiCom demonstration.

Both apps use version code 2 / version name 0.1.1. Public APKs use the existing
release certificate and can update the earlier public release while keeping data.
Configure a model service with your own credentials after a new installation.
The standard MobileClaw package includes OCR, image labels and text search;
optional MobileCLIP similarity weights remain a source-build option.

## 0.1 — Initial public research prototype

Initial public source, signed APKs, model-service settings, documentation, poster
and narrated MobiCom 2026 demonstration video.
