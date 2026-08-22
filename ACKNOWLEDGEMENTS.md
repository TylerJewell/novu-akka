# Acknowledgements

This project is a port of **[novuhq/novu](https://github.com/novuhq/novu)**, commit
`a58fe460f86f5fd6427d65bd6ba782378497e2cc`.

## Licence and copyright

- novuhq/novu is dual-licensed. Its `LICENSE-ENTERPRISE` states that everything under
  `enterprise/packages` is proprietary and that **content outside those directories is
  available under the MIT licence**. Every file this port was read from and run against
  sits under `libs/application-generic/` or `apps/worker/`, so all of it is MIT.
  `LICENSE-novu` here is novu's `LICENSE-MIT`, copied verbatim, and the copyright it
  carries is Noti-fire Apps Ltd.
- **No TypeScript was transcribed.** Every Java file under `src` was written fresh against
  behaviour read out of, and run against, the cloned source. Where a comment or the
  specification cites a source file or symbol, that is citation, not copying.
- **Behaviour is derived throughout**, and that is what a port is. The window arithmetic
  and its unit scale, the create/merge/skip decision and its backoff look-back, the
  configuration rules a digest step must satisfy, the reach of a firing digest, and the
  precedence order of channel preferences all come from the decision procedures in:
  - `libs/application-generic/src/services/calculate-delay/compute-job-wait-duration.service.ts`
  - `libs/application-generic/src/services/calculate-delay/timed-digest-delay.service.ts`
  - `apps/worker/src/app/workflow/usecases/add-job/merge-or-create-digest.usecase.ts`
  - `apps/worker/src/app/workflow/usecases/add-job/validation.ts`
  - `apps/worker/src/app/workflow/usecases/send-message/digest/get-digest-events-*.usecase.ts`
  - `libs/application-generic/src/usecases/get-subscriber-template-preference/get-subscriber-template-preference.usecase.ts`
  - `libs/application-generic/src/usecases/merge-preferences/merge-preferences.usecase.ts`

  The full evidence trail is in the harness repository's `novu-port/docs/question-log.md`
  and `novu-port/specs/SPEC-001-novu.md`.
- Several rejection messages are reproduced word for word — `Invalid digest amount`,
  `Digest timed config atTime is missing`, `Delay for next digest could not be
  calculated`, and the rest. They are reproduced because they are part of the behaviour
  being matched: a caller distinguishes an absent time of day from a badly formatted one
  by which message comes back. They are short factual strings under an MIT licence that
  permits their use, and they are listed here rather than left for a reader to notice.
- The MIT licence asks that the copyright notice and permission notice travel with copies
  and substantial portions of the software. `LICENSE-novu` carries both.

## Also used

- [Akka](https://akka.io) — the SDK and runtime this port is built on.
