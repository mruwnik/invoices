# Demo configs

Realistic invoice configurations that exercise the full pipeline end to
end. The point is to have a small set of fixtures you can eyeball when
you want to know "does everything still work for the cases I actually
care about" without having to hand-craft configs.

## Layout

```
demo/
├── configs/       realistic EDN configs for `clj -M:run`
├── golden/        committed FA(3) XML fixtures — regression guards
├── outputs/       generated PDFs and sidecars (gitignored)
├── run.sh         end-to-end driver
├── CHECKLIST.md   what to eyeball after a run
└── README.md      you are here
```

## The five configs

| config | headline case | KSeF? |
|---|---|---|
| `non-eu-services` | **Polish contractor → US company, art. 28b ust. 1** | no |
| `polish-domestic` | Polish seller → Polish buyer, mixed VAT | no |
| `polish-domestic-with-ksef` | same, with `:ksef {:env :test ...}` | yes |
| `eu-intra-community` | Polish seller → German B2B, art. 100 ust. 1 pkt 4 | no |
| `mixed-items` | every FA(3) bucket in one invoice | no |

`non-eu-services` is the PRIMARY use case — it's the scenario that
drove the compliance and wiring work. The other four exist to prevent
regressions in adjacent paths.

## Running

### End-to-end (produces PDFs + sidecars)

```
demo/run.sh                          # list configs
demo/run.sh non-eu-services          # run one
demo/run.sh --all                    # run every config
```

Outputs land under `demo/outputs/<config-name>/`. For
`polish-domestic-with-ksef`, export `KSEF_TEST_TOKEN` first:

```
export KSEF_TEST_TOKEN=<your-sandbox-token>
demo/run.sh polish-domestic-with-ksef
```

If the env var is unset the tool still generates the PDF; it just
logs a skip line for the KSeF step. This is deliberate — KSeF is an
additive sidecar, never a gate on rendering.

### Regression guards (fast, no PDF, no network)

```
clj -M:test
```

`test/invoices/demo_test.clj` loads every config, generates the FA(3)
XML via `invoices.ksef.xml/invoice->fa3-xml`, and compares to the
committed golden XML under `demo/golden/`. The `DataWytworzeniaFa`
timestamp is pinned via `with-redefs` so the comparison is byte-stable.

On top of the golden comparison, each config has a set of structural
substring assertions (e.g. `<P_13_8>14000.00</P_13_8>` for the
non-EU fixture) so a naive "regenerate goldens blindly" workflow still
hits a meaningful check.

**Note:** `polish-domestic.fa3.xml` and `polish-domestic-with-ksef.fa3.xml`
are byte-identical. That's expected, not a copy-paste mistake: `:ksef`
is facade metadata (env, token-env, schema) that drives submission,
not XML content. Two invoices with the same items + parties produce
the same FA(3) document regardless of whether either one is also
submitted to KSeF. Both fixtures are committed so the `-with-ksef`
config is covered if the facade ever starts shaping XML.

### Updating goldens

If you change a config (or the XML generator) and the diff is
intentional, regenerate:

```
UPDATE_DEMO_GOLDENS=1 clj -M:test
```

Then `git diff demo/golden/` and **read the diff** before committing.
Demo goldens are regression guards — a blind rewrite defeats the point.

## What the manual checklist covers

See `CHECKLIST.md` for the short list of things to eyeball on each PDF
and XML after a `demo/run.sh` invocation. It's deliberately short — the
golden tests handle the granular assertions; the checklist handles
"does this still look like an invoice a human would want to pay".
