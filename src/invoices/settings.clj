(ns invoices.settings
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [invoices.time :refer [date-applies?]]))

(defn load-config
  "Given a filename, load & return a config file"
  [filename]
  (-> filename slurp edn/read-string))

(defn filter-invoice-nips
  "Remove all invoices that don't apply to the given list of seller NIPs."
  [{invoices :invoices :as config} nips]
  (if (seq nips)
    (->> invoices
         (filter #(some #{(-> % :buyer :nip str)} nips))
         (assoc config :invoices))
    config))

(defn filter-unused-items
  "Remove all items from the given invoice that don't match the date."
  [{items :items :as invoice} date]
  (->> items
       (filter (partial date-applies? date))
       (assoc invoice :items)))

(defn filter-invoice-dates
  "Remove all items that don't apply to the given date, and also any with no items."
  [{invoices :invoices :as config} date]
  (->> invoices
       (map #(filter-unused-items % date))
       (filter (comp seq :items))
       (assoc config :invoices)))

(defn used-worklogs
  "Return all worklog ids used by the given invoices."
  [invoices]
  (->> invoices
       (map #(->> % :items (map :worklog)))
       flatten distinct set))

(defn filter-worklogs
  "Remove any worklogs that aren't used by the given invoices."
  [{invoices :invoices worklogs :worklogs :as config}]
  (->> worklogs
       (filter (comp seq #(set/intersection % (used-worklogs invoices)) set :ids))
       (assoc config :worklogs)))

(defn invoices
  "Get all invoices that apply to the given month and (optional NIPs)."
  [config month & [nips]]
  (-> config load-config
      (filter-invoice-nips nips)
      (filter-invoice-dates month)
      filter-worklogs))
