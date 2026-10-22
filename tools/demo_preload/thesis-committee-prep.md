# Thesis committee meeting — prep notes

Committee: Prof. Gao (advisor), Prof. Mosse, Prof. Zhang, external member Dr. Lee (CMU).
Room: Benedum 1234. 90 minutes: 30 min talk, 60 min questions.

Likely questions:
- Why a cloud model rather than an on-device one? Answer: latency and quality today; the
  endpoint is swappable, and Qwen3-8B runs on the phone but slowly.
- How is the model kept from acting on injected instructions? Confirm gate + sanitizer +
  privilege separation over Binder.
- What is the energy cost per notification? Measure before the defense.

To do after the meeting: send the slides to the committee, update the timeline, book the
room for the next check-in in December.
