(ns invoices.timesheets
  (:require [clj-http.client :as client]
            [invoices.email :as email]
            [invoices.time :refer [last-day prev-month]]))

(defn tempo [{tempo-token :tempo-token} endpoint params]
  (-> (str "https://api.tempo.io/core/3" endpoint)
      (client/get {:oauth-token tempo-token :as :json :query-params params})
      :body))

(defn jira [{jira-user :jira-user jira-token :jira-token} endpoint]
  (-> (str "https://clearcodehq.atlassian.net/rest/api/3/" endpoint)
      (client/get {:basic-auth [jira-user jira-token] :as :json})
      :body))

(defn me [credentials] (:accountId (jira credentials "/myself")))

(defn timesheet [{spent :timeSpentSeconds required :requiredSeconds period :period}]
  (merge {:worked (/ spent 3600) :required (/ required 3600)} period))

(defn jira-timesheet [who when credentials]
  (let [log (->> {"from" (-> when (.withDayOfMonth 1) .toString) "to" (-> when last-day .toString)}
                 (tempo credentials (str "/timesheet-approvals/user/" who))
                 (timesheet))]
    (map (partial assoc log :id) (:ids credentials))))

(defn prev-timesheet
  "Get the timesheet for the previous month"
  [when credentials]
  (clojure.core/when (:jira-user credentials)
    (jira-timesheet (me credentials) (prev-month when) credentials)))

(defn hours-from-list [date {:keys [ids worklogs]}]
  (let [month (-> date (.toString) (subs  0 7))
        {:keys [count unit]} (get worklogs month)
        worklog {:worked (and count (* count ({:hour 1 :day 8} unit 1)))}]
    (map (partial assoc worklog :id) ids)))

(defn get-timesheet [month {type :type offset :month-offset :as creds}]
  (let [month (-> month (.plusMonths (or offset 0)))]
    (condp = type
      :jira (jira-timesheet (me creds) month creds)
      :imap (email/get-worklogs month creds)
      :list (hours-from-list month creds))))

(defn timesheets
  "Return timesheets for the given month from the given worklogs."
  [month worklogs]
  (->> worklogs
       (map (partial get-timesheet month))
       flatten
       (map (fn [{id :id :as log}] [id log]))
       (into {})))
