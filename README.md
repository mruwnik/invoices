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

The config file should be a EDN file containing a list of invoices. Each invoice can have the
following keys:

 * :seller      - the seller's (i.e. the entity to be paid) information. This is required
 * :buyer       - the buyer's (i.e. the entity that will pay) information. This is required
 * :items       - a list of items to be paid for
 * :font-path   - (optional) *the path to a font file, e.g. "/usr/share/fonts/truetype/freefont/FreeSans.ttf"
 * :credentials - JIRA and Tempo access credentials. These are needed if the price depends on tracked time

See `resources/config.edn` for an example configuration.

 ### Seller

 The Seller can have the following keys:

 * :name    - (required) the name of the seller, e.g. "Mr. Blobby"
 * :address - (required) the address of the seller, e.g. "ul. Szeroka 12, 12-345, Buty"
 * :nip     - (required) the NIP of the seller, e.g. 1234567890
 * :account - (required) the number of the account to which the payment should go, e.g. "12 4321 8765 1000 0000 1222 3212"
 * :bank    - (required) the name of the bank in which the account is held, e.g. "Piggy bank"
 * :phone   - (optional) the phone number of the seller 555333111
 * :team    - (optional) a team name, to be prepended to the name of the resulting pdf, e.g. "the A team"


### Buyer

The buyer can have the following keys

 * :name    - (required) the name of the seller, e.g. "Mr. Blobby"
 * :address - (required) the address of the seller, e.g. "ul. Szeroka 12, 12-345, Buty"
 * :nip     - (required) the NIP of the seller, e.g. 1234567890

### Items

The list of items should contain maps with a required :title, optional :vat (if not provided it is assumed that
item is VAT free), :to (the date from which this item is valid), :from (the date till which this item is valid)
and a key providing the cost of the item. The price can be provided in one of the following ways:

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
                       operations (+, -, /, *) and timesheet specific variables are supported (:worked, :required,
                       :to, :from).


Examples:

    ; 8% VAT, and a price of 600, recurring every period before 2019-05-30
    {:vat 8 :netto 600 :title "Shoes" :to "2019-05-30"}

    ; 12% VAT, and an hourly rate of 12, first appearing on 2019-07-01
    {:vat 12 :hourly 12 :title "Something worth 12/h" :from "2019-07-01"}

    ; 23% VAT, working part time with a base salary of 5000
    {:vat 23 :base 5000 :per-day 4 :title "Part time job at 5000"}]

    ; 23% VAT, with a custom function
    {:vat 23 :function (* :worked (/ 10000 :required)) :title "Custom function"}]


### Credentials

In the case of hourly rates or variable hours, the number of hours worked needs to be fetched
from a time tracker. Which requires appropriate credentials. See
[Jira's](https://developer.atlassian.com/cloud/jira/platform/jira-rest-api-basic-authentication/)
and [Tempo's](https://tempo-io.atlassian.net/wiki/spaces/KB/pages/199065601/How+to+use+Tempo+Cloud+REST+APIs)
documentation on how to get the appropriate tokens. Once the tokens are generated, the :credentials
should look like the following:

    :credentials {:tempo-token "5zq7zF9LADefEGAs12eDDas3FDttiM"
                  :jira-token "qypaAsdFwASasEddDDddASdC"
                  :jira-user "mr.blobby@boots.rs"}
