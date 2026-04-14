# Manual smoke checklist

Run `demo/run.sh --all` (or one config at a time) and then eyeball the
artifacts. This is the short list of things to look at after each demo.

## After EVERY run

- [ ] The PDF file exists under `demo/outputs/<config>/`.
- [ ] Open the PDF. Seller name, address, and NIP are correct.
- [ ] Buyer name and address are correct. For the non-EU case, the US
      address is clearly foreign (no PL NIP line).
- [ ] The line-item table lists every item in the config with the right
      title, quantity, net, VAT label, and brutto.
- [ ] The VAT-summary row at the bottom matches the sum of the item
      rows — no rogue rounding, no duplicated buckets.
- [ ] The invoice total at the bottom of the PDF matches the arithmetic
      sum of all line brutto values.

## Extra checks per config

### `non-eu-services` — Polish contractor → US company, art. 28b

This is the user's primary case. Inspect the FA(3) XML under
`demo/golden/non-eu-services.fa3.xml` (or regenerate it with
`UPDATE_DEMO_GOLDENS=1 clj -M:test` if the config changed).

- [ ] `<Podmiot2>` contains `<BrakID>1</BrakID>` — **no** `<NIP>` element.
- [ ] `<Adres>` under `<Podmiot2>` has `<KodKraju>US</KodKraju>`.
- [ ] Every `<P_12>` row says `np I` (not `23`, not `zw`).
- [ ] `<P_13_8>` equals the sum of all three line nets: `14000.00`.
- [ ] No `<P_14_8>` element — FA(3) has no such bucket.
- [ ] `<P_15>` equals `14000.00` — brutto = net, because no Polish VAT.
- [ ] `<DodatkowyOpis>` is present and cites **art. 28b**.
- [ ] On the PDF, the VAT column on every line shows `np I`, not `0%`.

### `polish-domestic` and `polish-domestic-with-ksef`

- [ ] Buyer `<NIP>` is `9875645342`.
- [ ] `<P_13_1>` = `1200.00`, `<P_14_1>` = `276.00` (23% of 1200).
- [ ] `<P_13_2>` = `450.00` (8% line).
- [ ] `<P_13_3>` = `200.00` (5% line).
- [ ] `<P_13_7>` = `100.00` (zw line).
- [ ] `<P_19>` = `1` — the Zwolnienie flag is on because there's a zw line.
- [ ] For the `-with-ksef` config, if `KSEF_TEST_TOKEN` is set in the
      environment, a `*.ksef.xml` and `*.upo.xml` show up next to the
      PDF and the console prints `KSeF accepted: <numer>`. If the env
      var is not set, the console prints a skip line and ONLY the PDF
      is created — the rest of the pipeline is unaffected.

### `eu-intra-community` — Polish seller → German B2B buyer

- [ ] `<Podmiot2>` uses `<KodUE>DE</KodUE>` + `<NrVatUE>123456789</NrVatUE>`
      — note the `DE` prefix has been stripped from the VAT-UE number
      (that is the schema's rule, not a bug).
- [ ] Every `<P_12>` row says `np II`.
- [ ] `<P_13_9>` equals `9000.00` (sum of the two lines).
- [ ] `<DodatkowyOpis>` cites **art. 100 ust. 1 pkt 4**.

### `mixed-items` — every bucket in one invoice

- [ ] All seven bucket elements appear: `P_13_1`, `P_13_2`, `P_13_3`,
      `P_13_6_1`, `P_13_7`, `P_13_8`, `P_13_9`.
- [ ] VAT (P_14_N) is only present for integer-rate buckets (1, 2, 3),
      not for 0 KR / zw / np / np-eu.
- [ ] `<DodatkowyOpis>` contains **both** `np I` and `np II` legal-basis
      entries because both classifications are present.
- [ ] `<P_15>` total brutto = 8232.50 (1230 + 540 + 262.50 + 125 + 75
      + 4000 + 2000).

## When something looks wrong

1. Re-run `clj -M:test` — the demo tests compare against committed
   golden XML. If they fail, the mismatch tells you exactly which
   element drifted.
2. If the mismatch is INTENTIONAL (you changed a config or the XML
   generator), regenerate goldens with
   `UPDATE_DEMO_GOLDENS=1 clj -M:test` and re-inspect the diff before
   committing.
3. If the PDF layout is broken but the XML is right, the bug is in
   `invoices.pdf`, not the FA(3) generator.
4. If the XML is wrong but the PDF is right, the bug is in
   `invoices.ksef.xml`, and the existing `invoices.ksef.xml-test`
   suite will have more targeted repro than the demo tests.
