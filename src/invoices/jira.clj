(ns invoices.jira
  (:require [clj-http.client :as client]
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

(defn get-timesheet [who when credentials]
  (->> {"from" (-> when (.withDayOfMonth 1) .toString) "to" (-> when last-day .toString)}
       (tempo credentials (str "/timesheet-approvals/user/" who))
       (timesheet)))

(defn prev-timesheet
  "Get the timesheet for the previous month"
  [when credentials]
  (clojure.core/when (:jira-user credentials)
    (get-timesheet (me credentials) (prev-month when) credentials)))
