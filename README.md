# Invoices

Generate invoices from a config file

## Basic usage

Use the following to see how it works on the provided sample config (make sure to update
the JIRA credentials with correct values).

    lein run resources/config.edn


## Options

The following options are available:

    -n, --number   Invoice number. In the case of multiple invoices, they will have subsequent numbers
    -w, --when     The month for which to generate the invoice
    -c, --company  The NIPs of companies for which to generate invoices. If not provided, all the companies will be used
    -h, --help     Display a help message

## Config file

The config file should be a EDN file containing a list of invoices, seller info,
optional font info and optional worklogs info, e.g.:

    {:seller {(...)}
     :invoices [(...)]
     :font-path "/path/to/font"
     :worklogs [(...)]}


`:font-path` should be the path to a font file, e.g. "/usr/share/fonts/truetype/freefont/FreeSans.ttf"
See `resources/config.edn` for an example configuration.

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

The list of items should contain maps with a required :title, optional :vat (if not provided it is assumed that
item is VAT free), :to (the date from which this item is valid), :from (the date till which this item is valid),
:notes (a list of extra notes to be added at the bottom of the invoice) and a key providing the cost of the item.
The price can be provided in one of the following ways:

 * :netto            - is a set price and will be displayed as provided
 * :hourly           - is an hourly price - JIRA will be queried in order to work out how many hours should be billed
 * :base + :per-day  - in the case of a variable number of hours worked. :base provides the amount that would be paid
                       if `<number of hours worked> == <number of hours in the current month if full time> / per-day`.
                       In the case of someone working full time, :per-day would be 8, and if the number of hours worked
                       is the same as the number of working hours in the month, the final price would simply be :base.
                       If someone worked part time, e.g. 4 hours daily, then :per-day would be 4, and if that person
                       had worked exactly half the number of working hours in a given month, then the price will also
                       be :base. Otherwise the final price will be scaled accordingly. This is pretty much equivalent
                       to working out what the hourly rate should be in a given month and multiplying it by the number
                       of hours worked in that month
 * :function         - an S-expression describing how to calculate the net value. Only numbers, basic mathematical
                       operations (+, -, /, *) and timesheet specific variables are supported (:worked, :required).

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

#### Jira

See [Jira's](https://developer.atlassian.com/cloud/jira/platform/jira-rest-api-basic-authentication/)
and [Tempo's](https://tempo-io.atlassian.net/wiki/spaces/KB/pages/199065601/How+to+use+Tempo+Cloud+REST+APIs)
documentation on how to get the appropriate tokens. Once the tokens are generated, add an appropriate
worklog entry like the following:

    :worklogs [{:type :jira
                :ids [:from-jira]
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
