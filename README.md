# Invoices

Generate invoices from a config file

## Basic usage

Use the following to see how it works on the provided sample config (make sure to update
the JIRA credentials with correct values).

    clj -M:run resources/config.edn

The project uses `deps.edn` (tools.deps) rather than `project.clj`; there is no
longer a Leiningen build file. The `:run` alias invokes `invoices.core/-main`
and the `:test` alias runs the test suite via `clj -X:test`.


## Options

The following options are available:

    -n, --number   Invoice number. In the case of multiple invoices, they will have subsequent numbers
    -w, --when     The date for which to generate the invoice
    -c, --company  The NIPs of companies for which to generate invoices. If not provided, all the companies will be used
    -h, --help     Display a help message

## Config file

The config file should be a EDN file containing a list of invoices, seller info,
optional font info and optional worklogs info, e.g.:

    {:seller {(...)}
     :invoices [(...)]
     :font-path "/path/to/font"
     :worklogs [(...)]}


`:font-path` should be the path to a font file, e.g. `"/usr/share/fonts/truetype/freefont/FreeSans.ttf"`
See [`resources/config.edn`](https://github.com/mruwnik/invoices/blob/master/resources/config.edn) for an example configuration.

 ### Seller

 The Seller can have the following keys:

 * :name    - (required) the name of the seller, e.g. "Mr. Blobby"
 * :address - (required) the address of the seller, e.g. "ul. Szeroka 12, 12-345, Buty"
 * :nip     - (required) the NIP of the seller, e.g. 1234567890
 * :account - (optional) the number of the account to which the payment should go, e.g. "12 4321 8765 1000 0000 1222 3212"
 * :bank    - (optional) the name of the bank in which the account is held, e.g. "Piggy bank"
 * :phone   - (optional) the phone number of the seller 555333111
 * :team    - (optional) a team name, to be prepended to the name of the resulting pdf, e.g. "the A team"

### Invoices

Each invoice can have the following keys:

 * :buyer       - the buyer's (i.e. the entity that will pay) information. This is required
 * :items       - a list of items to be paid for
 * :imap        - (optional) email credentials. These are needed if a confirmation email is to be sent
 * :callbacks   - (optional) a list of commands to be called with the resulting pdf file
 * :seller      - (optional) invoice specific overrides for the seller object

### Buyer

The buyer can have the following keys

 * :name    - (required) the name of the seller, e.g. "Mr. Blobby"
 * :address - (required) the address of the seller, e.g. "ul. Szeroka 12, 12-345, Buty"
 * :nip     - (required) the NIP of the seller, e.g. 1234567890
 * :email   - (optional) the email of the buyer, e.g. "faktury@bla.com". This is required if a confirmation email is to be sent

### Items

The list of items should contain maps with a required `:title`, optional `:vat` (if not provided it is assumed that
item is VAT free), `:to` (the date from which this item is valid), `:from` (the date till which this item is valid),
`:notes` (a list of extra notes to be added at the bottom of the invoice) and a key providing the cost of the item.
The price can be provided in one of the following ways:

 * :netto            - is a set price and will be displayed as provided
 * :brutto           - is a set price and will be first scaled down to netto
 * :hourly           - is an hourly price - worklogs will be queried in order to work out how many hours should be billed.
                       If no worklog could be found (or its :worked is nil), this item will be skipped.
 * :base + :per-day  - in the case of a variable number of hours worked. :base provides the amount that would be paid
                       if `<number of hours worked> == <number of hours in the current month if full time> / per-day`.
                       In the case of someone working full time, :per-day would be 8, and if the number of hours worked
                       is the same as the number of working hours in the month, the final price would simply be :base.
                       If someone worked part time, e.g. 4 hours daily, then :per-day would be 4, and if that person
                       had worked exactly half the number of working hours in a given month, then the price will also
                       be :base. Otherwise the final price will be scaled accordingly. This is pretty much equivalent
                       to working out what the hourly rate should be in a given month and multiplying it by the number
                       of hours worked in that month. If no `:worked` value can be found (or if it's nil), this item
                       will be skipped.
 * :function         - an S-expression describing how to calculate the net value. Only numbers, basic mathematical
                       operations (+, -, /, *) and timesheet specific variables are supported (:worked, :required).
                       If a timesheet variable is used, but no such value can be found in the timesheet, an exception
                       will be raised.
 * :from               - an ISO date specifying the date from which this item should be used in calculating invoices (any invoices generated for dates before this value will ignore this item)
 * :to               - an ISO date specifying the date up to which this item should be used in calculating invoices (any invoices generated for dates after this value will ignore this item)

If the price is to be calculated on the basis of a worklog, add a `:worklog` key
and make sure the `:worklogs` section has an item that can be used to access the worklog.

Examples:

    ; 8% VAT, and a price of 600, recurring every period before 2019-05-30
    {:vat 8 :netto 600 :title "Shoes" :to "2019-05-30" :notes ["A note at the bottom"]}

    ; 12% VAT, and an hourly rate of 12, first appearing on 2019-07-01
    {:vat 12 :hourly 12 :title "Something worth 12/h" :from "2019-07-01" :worklog "washed_dishes"}

    ; 23% VAT, working part time with a base salary of 5000
    {:vat 23 :base 5000 :per-day 4 :title "Part time job at 5000" :worklog "cleaned_shoes"}]

    ; 23% VAT, with a custom function
    {:vat 23 :function (* :worked (/ 10000 :required)) :title "Custom function"} :worklog :from-jira]


### Worklogs

In the case of hourly rates or variable hours, the number of hours worked needs to be fetched
from a time tracker. Which requires appropriate credentials, described in the
following sections. Apart from provider specific values, each credentials map
must contain a `:type` key that describes the provider, and a `:ids` list, which
should contain all worklog ids that can be found in the given worklog. These ids
are used to link worklog values with items via the `:worklog` key of items.

#### Simple lists

This is the basic worklog, i.e. a list of months with the amount worked provided (hours by default).
The unit can be changed via the `unit` key and can be one of `:hour` or `:day`. Below is an example:

    :worklogs [{:type :list
                :ids [:cows-R-us]
                :worklogs {"2020-09" {:count 12 :unit :day}
                           "2020-10" {:count 12 :unit :day}
                           "2020-11" {:count 54 :unit :hour}
                           "2020-12" {:count 5 :unit :day}
                           "2021-02" {:count 20 :unit :day}}}]

#### Jira

See [Jira's](https://developer.atlassian.com/cloud/jira/platform/jira-rest-api-basic-authentication/)
and [Tempo's](https://tempo-io.atlassian.net/wiki/spaces/KB/pages/199065601/How+to+use+Tempo+Cloud+REST+APIs)
documentation on how to get the appropriate tokens. Once the tokens are generated, add an appropriate
worklog entry like the following:

    :worklogs [{:type :jira
                :ids [:from-jira]
                :month-offset -2  ; Can be used to get a different month than the currently processed one. In this case, 2 months previous
                :tempo-token "5zq7zF9LADefEGAs12eDDas3FDttiM"
                :jira-token "qypaAsdFwASasEddDDddASdC"
                :jira-user "mr.blobby@boots.rs"}]

#### Emails

Emails with worklogs should be sent in a psudo csv format, seperated by `;` or
whitespace. Use the `:headers` key to describe what data is contained in each
column.
The emails are looked for in the `:folder` folder of the email account, and all
emails from `:from` (or anyone if `:from` is nil or missing) and with the subject
contining `:subject` formatted with the processed date.

Assuming the processed date is 2012-12-12, and the following configuration is provided:

    :worklogs [{:type :imap
                :ids [:item1 :item2]
                :folder "inbox"
                :host "imap.gmail.com"
                :user "mr.blobby@boots.rs"
                :pass "lksjdfklsjdflkjw"
                :from "hr@boots.rs"
                :subject "'Hours 'YYYY-MM"
                :headers [:id :worked]}]

if `hr@boots.rs` sends an email to the `inbox` folder of `mr.blobby@boots.rs`'s
email account with the title `Hours 2012-12` and the following contents (notice the underscores):

    washed_dishes; 12
    cleaned_shoes; 43

the following work logs will be found:

    [{:id "washed_dished" :worked 12}
     {:id "cleaned_shoes" :worked 43}]

## Confirmation emails

Each invoice can also be sent via email to the appropriate seller. For this to work, the buyer must
have an :email key set and a :smtp key with the :smtp settings for the email server should be provided.

     :invoices [{:buyer {(...) :email "accounting@boots.rs"}
                 (...)
                 :smtp {:host "smtp.gmail.com"
                        :user "mr.blobby@buty.sa"
                        :pass "asd;l;kjsdfkljld"
                        :ssl true}}]

The `:email` value can be a string (i.e. a single email address) or a list of strings.

## KSeF (Polish e-invoicing)

KSeF (Krajowy System e-Faktur) is the Polish Ministry of Finance's national
e-invoicing system. When an invoice has a `:ksef` key, this tool generates the
PDF as usual and additionally submits a **FA(3)**-schema XML document to KSeF,
receiving a permanent numer KSeF and a signed UPO (Urzędowe Poświadczenie
Odbioru) confirmation. Three environments are supported: `:test`
(`api-test.ksef.mf.gov.pl`), `:demo` (`api-demo.ksef.mf.gov.pl`) and `:prod`
(`api.ksef.mf.gov.pl`).

### One-time setup

1. Log in to the KSeF taxpayer panel at [podatki.gov.pl](https://podatki.gov.pl) → KSeF.
2. Generate a token with permissions `InvoiceRead` + `InvoiceWrite` in the role matching the seller NIP.
3. Copy the token value somewhere safe — it will be shown once.

### Config shape

Define `:ksef` **once** at the seller level and every invoice in the
config inherits it:

    {:seller {:name "Mr. Blobby"
              :nip 6423166047
              :ksef {:env :test
                     :token-env "KSEF_TOKEN"
                     :schema :fa-3}}
     :invoices [{:buyer {(...)} :items [(...)]}      ; inherits → submits
                {:buyer {(...)} :items [(...)]}]}    ; inherits → submits

Keys inside the `:ksef` map:

 * `:env` — `:test` | `:demo` | `:prod` — which KSeF environment to submit to. Start with `:test` until you have a known-good flow.
 * `:nip` — the seller's NIP as a number or string. Must match the context of the token. **Optional** at the `:ksef` level: if omitted, it defaults to the `:seller :nip` at the top of the config (which you already have to set for the PDF anyway).
 * `:token-env` — the **name** of an environment variable that holds the token. The token itself is NEVER written to `config.edn`; the tool reads it from the environment at runtime. If the env var is unset, submission for that invoice is skipped with a log line (other invoices still process).
 * `:schema` — `:fa-3`. `:fa-2` is legacy: still accepted by prod for backward-compatibility within the Ministry of Finance's deprecation window, but don't use it for new integrations. Stick with FA(3).

#### Per-invoice override

An invoice can override individual keys (or opt out entirely) by setting
its own `:ksef` block. Invoice-level keys **merge over** the seller-level
block — you only have to specify what changes:

    :invoices [{:buyer {(...)} :items [(...)]}                     ; inherits seller :ksef
               {:buyer {(...)} :items [(...)] :ksef {:env :prod}}  ; override just :env
               {:buyer {(...)} :items [(...)] :ksef nil}           ; explicit opt-out (PDF only)
               {:buyer {(...)} :items [(...)] :ksef {:nip 999}}]   ; branch billing — override NIP

Merge semantics:

 * Invoice has no `:ksef` key → **inherit** the seller's block verbatim.
 * Invoice has `:ksef` as a map → `(merge seller-ksef invoice-ksef)`. Invoice keys win on individual fields; an empty map `{}` is equivalent to inheritance.
 * Invoice has `:ksef nil` (or `false`) → **explicit opt-out**. This invoice generates a PDF but does NOT submit to KSeF, even if the seller has a `:ksef` block. Useful for a mixed run.

The **legacy per-invoice shape** — `:ksef` on the invoice with no seller-level
block — still works unchanged; the merge is simply against an empty base.

Invoices without any `:ksef` (neither at seller nor invoice level) behave
exactly as before (PDF only).

### Running

    export KSEF_TOKEN=<your-token>
    clj -M:run resources/config.edn

For every invoice with a `:ksef` block, the tool will:

1. Generate the PDF as usual.
2. Build the FA(3) XML and submit it via the KSeF online session API.
3. Save two sidecar files next to the PDF: `<invoice-title>.ksef.xml` (the document that was sent) and `<invoice-title>.upo.xml` (the UPO returned by KSeF).
4. Print `" - KSeF accepted: <numer-ksef>"` on success.

If the submission fails for any reason (network, auth, validation, sandbox
downtime), the failure is logged as `" - KSeF FAILED: <error>"` and the tool
continues processing the remaining invoices. **The PDF is always generated,
even when KSeF submission fails** — KSeF is an additive sidecar, never a gate
on invoice rendering.

### Integration tests

Unit tests mock every HTTP call and run without credentials. There is also an
end-to-end integration test (`invoices.ksef.integration-test`) that submits a
real invoice against the TEST sandbox; it self-skips when the required env
vars are absent, so CI without credentials passes cleanly.

To run it locally, export the three vars and run the full test suite:

    export KSEF_TEST_TOKEN=<your-test-token>
    export KSEF_TEST_NIP=<nip-matching-the-token>
    export KSEF_TEST_BASE=https://api-test.ksef.mf.gov.pl/v2
    clj -X:test

The `:test` alias runs every `*_test.clj` namespace under `test/`. The KSeF
integration test self-skips when `KSEF_TEST_TOKEN` is unset, so running
`clj -X:test` without exporting these variables is the normal unit-test
path — you'll still get a clean green run, just with the integration case
reported as skipped. Exporting the vars flips it on in place; there is no
separate command to run "just the integration test."

### Using the DEMO environment

`:env :demo` targets `api-demo.ksef.mf.gov.pl`. The DEMO environment is
more production-like than `:test` — it has tenant-isolated data and
is closer to PROD's behavior — while still being a sandbox that won't
bill anyone. Point any of the three entry points at it by changing just
the environment:

 * **At runtime** (`clj -M:run` against a config): set `:env :demo` in
   the `:ksef` block. The URL lookup in `invoices.ksef/base-urls` handles
   the routing; nothing else in the submission chain is environment-aware.
 * **Integration test** (`clj -M:test` with credentials exported):
   `export KSEF_TEST_BASE=https://api-demo.ksef.mf.gov.pl/v2` alongside
   `KSEF_TEST_TOKEN` / `KSEF_TEST_NIP`. The integration test reads the
   base URL from the env var verbatim; the "TEST" in the env var name is
   historical, not a pin to the `:test` environment.
 * **Bootstrap script** (`scripts/ksef_bootstrap.clj`): pass
   `https://api-demo.ksef.mf.gov.pl/v2` as the first CLI argument instead
   of the TEST URL. The script is env-agnostic — only the docstring
   example happens to show `api-test`.

**Cert caveat**: `:test` accepts self-signed RSA-2048 organization-seal
certs (see the bootstrap docstring). DEMO is closer to PROD and may
require a qualified cert (SZAFIR or equivalent) instead — this repo has
not been exercised end-to-end against DEMO, so if a bootstrap run fails
at the XAdES signature step with a 4xx from `/auth/xades-signature`,
the most likely cause is cert policy, not our signing code. Obtain a
qualified cert via the KSeF taxpayer panel before troubleshooting.

Note that the KSeF sandbox has daily maintenance windows around 16:00–18:00
Europe/Warsaw; failures during that window are environmental, not bugs.

### Security

The KSeF token is a bearer credential — anyone with the token string can submit
invoices in your name. Treat it like a password:

 * Keep it in an environment variable or a secrets manager.
 * Never commit it to git and never put it in `config.edn`.
 * Rotate it if it may have been exposed.
 * The tool reads the token into memory for the duration of a submission and never writes it to disk.

### Demo configs

There is a set of realistic demo configs under `demo/configs/` you can
use to eyeball the full pipeline end to end — PDF rendering, FA(3) XML
generation, and (optionally) KSeF submission. The configs cover the
Polish-contractor-to-US-company `:vat :np` case (the primary use case),
a domestic Polish invoice, a domestic Polish invoice with a KSeF block,
an intra-EU B2B case, and a "every VAT bucket at once" stress fixture.

Run them with:

    demo/run.sh                        # list available configs
    demo/run.sh non-eu-services        # run one
    demo/run.sh --all                  # run every config

Each run drops artifacts under `demo/outputs/<config>/` (gitignored).
A companion golden test at `test/invoices/demo_test.clj` compares the
generated FA(3) XML against committed fixtures in `demo/golden/` so
drift in the generator is loud. See `demo/README.md` and
`demo/CHECKLIST.md` for the full workflow and the manual eyeball list.

## Callbacks

A list of additional commands can be added to each invoice. Each command will be called with
the generated invoice as its final parameter, e.g.

    {:seller {...}
     :buyer {...}
     :items [...]
     :callbacks [["ls" "-l"] ["rm"] ["du" "-sh"]]}

Will call the following commands (assuming that the generated invoice is `/path/to/file.pdf`):

    ls -l /path/to/file.pdf
    du -sh /path/to/file.pdf
    rm /path/to/file.pdf

The last one will obviously fail, as the file no longer exists, and the error message will be displayed
