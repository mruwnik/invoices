(ns invoices.time)


(defn last-day
  "Get the last day of the month of the given `date`"
  [date] (-> date (.withDayOfMonth 1) (.plusMonths 1) (.minusDays 1)))

(defn prev-month
  "Get the first day of the month preceeding the given `date`"
  [date] (-> date (.withDayOfMonth 1) (.minusMonths 1)))

(defn skip-days-off
  "Return the first day before (and including) `date` that isn't a day off."
  [date]
  (if (some #{(.getDayOfWeek date)} [java.time.DayOfWeek/SATURDAY java.time.DayOfWeek/SUNDAY])
    (skip-days-off (.minusDays date 1)) date))

(defn last-working-day
  "Get the last working day of `date`'s month."
  [date]
  (-> date (.withDayOfMonth 1) (.plusMonths 1) (.minusDays 1) skip-days-off))

(defn date-applies?
  "Return whether the provided `date` is between the provided :to and :from dates."
  [date {to :to from :from}]
  (and (or (nil? to) (-> date .toString (compare to) (< 0)))
       (or (nil? from) (-> date .toString (compare from) (>= 0)))))
