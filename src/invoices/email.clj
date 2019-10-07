(ns invoices.email
  (:require [clojure.string :as str]
            [invoices.time :as time]
            [postal.core :refer [send-message]]
            [clojure-mail.core :as mail]
            [clojure-mail.gmail :as gmail]
            [clojure-mail.folder :as folder]
            [clojure-mail.message :refer (read-message) :as mess]))

(defn send-email [to from {smtp :smtp} invoice]
  (when (not-any? nil? [to from smtp invoice])
    (->>
     (send-message smtp {:from from
                         :to [to]
                         :subject invoice
                         :body [{:type :attachment
                                 :content (java.io.File. (str invoice ".pdf"))
                                 :content-type "application/pdf"}]})
     :error (= :SUCCESS)
     (println " - email sent: "))))


(defn server-find-messages
  "Find all messages in the given folder, filtering them by subject and sender (use nil to ignore)."
  [client folder subject from]
  (->>
   {:subject subject :from from}
   (remove (comp nil? second))
   flatten
   (apply folder/search (mail/open-folder client folder :readonly))
   (into [])))

(defn manual-find-messages
  "Find all messages in the given folder, filtering them by subject and sender (use nil to ignore).

  WARNING: This can be very slow, as it fetches each message seperately.
  "
  [client folder subject from]
  (let [subjecter (if subject #(str/includes? (mess/subject %) subject) identity)
        fromer (if from #(str/includes? (-> % mess/from first :address) from) identity)]
    (filter
     #(and (subjecter %) (fromer %))
     (mail/all-messages client folder))))

(defn find-messages
  "Get all messages for the given month from the given imap server."
  [month
   {imap :host user :user password :pass try-manual :try-manual folder :folder from :from subject :subject}]
  (let [client (mail/store "imaps" imap user password)
        subject (time/format-month subject month)
        messages (server-find-messages client folder subject from)]
    (if (and (empty? messages) try-manual)
      (manual-find-messages client folder subject from)
      messages)))

(defn split-cells [content]
  (->> content
       str/split-lines
       (remove str/blank?)
       (map #(str/split % #"[\s;]"))))

(defn zip-item [headers cell]
  (into (sorted-map) (map vector headers cell)))

(defn extract-items [headers message]
  (->> message
       mess/get-content
       split-cells
       (map (partial zip-item headers))))

(defn get-worklogs
  "Get all worklogs for the given month from the given imap server."
  [month imap]
  (->> imap (find-messages month) (map (partial extract-items (:headers imap))) flatten))
