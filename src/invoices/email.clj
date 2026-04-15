(ns invoices.email
  (:require [clojure.string :as str]
            [invoices.time :as time])
  (:import [jakarta.mail Session Transport Message$RecipientType Store Folder Multipart Part]
           [jakarta.mail.internet MimeMessage InternetAddress MimeMultipart MimeBodyPart]
           [jakarta.mail.search AndTerm SubjectTerm FromStringTerm SearchTerm]
           [jakarta.activation DataHandler FileDataSource]
           [java.util Properties]))

(defn smtp-properties
  "Build a Properties instance for a Jakarta Mail SMTP Session from an smtp
  config map. `:ssl` selects SMTPS (default port 465), `:tls` selects STARTTLS
  (default port 587), otherwise plain SMTP on port 25."
  ^Properties [{:keys [host port ssl tls]}]
  (let [props (Properties.)
        default-port (cond ssl 465 tls 587 :else 25)]
    (.put props "mail.smtp.host" (str host))
    (.put props "mail.smtp.port" (str (or port default-port)))
    (.put props "mail.smtp.auth" "true")
    (when tls
      (.put props "mail.smtp.starttls.enable" "true")
      (.put props "mail.smtp.starttls.required" "true"))
    (when ssl
      (.put props "mail.smtp.ssl.enable" "true")
      (.put props "mail.smtp.socketFactory.class" "javax.net.ssl.SSLSocketFactory"))
    props))

(defn build-message
  "Construct a MimeMessage with a single PDF attachment (multipart/mixed: blank
  text part + file). Exposed so tests can assert structure without a live SMTP."
  ^MimeMessage [^Session session ^String from ^String to ^String subject ^java.io.File attachment]
  (let [msg (MimeMessage. session)
        text-part (doto (MimeBodyPart.) (.setText ""))
        file-part (doto (MimeBodyPart.)
                    (.setDataHandler (DataHandler. (FileDataSource. attachment)))
                    (.setFileName (.getName attachment)))
        multipart (doto (MimeMultipart.)
                    (.addBodyPart text-part)
                    (.addBodyPart file-part))]
    (doto msg
      (.setFrom (InternetAddress. from))
      (.setRecipient Message$RecipientType/TO (InternetAddress. to))
      (.setSubject subject)
      (.setContent multipart))))

(defn send-message!
  "Side-effecting Transport/send wrapper — tests with-redefs this to avoid SMTP."
  [^MimeMessage msg ^String user ^String pass]
  (Transport/send msg user pass))

(defn send-invoice [file to {from :user password :pass :as smtp}]
  (if (sequential? to)
    (doseq [address to] (send-invoice file address smtp))
    ;; Nil-check the individual locals we actually depend on, not the
    ;; whole smtp map. The old `(not-any? nil? [to from smtp file])`
    ;; form looked correct but was load-bearing on `smtp` being the
    ;; raw map (never nil in practice because callers always pass a
    ;; destructurable map), so a config missing `:user` would pass
    ;; the guard and then explode in `InternetAddress.` with an
    ;; opaque NPE. Checking `from` and `password` directly catches
    ;; that at the boundary and no-ops cleanly instead.
    (when (and (not-any? nil? [to from password file])
               (try
                 (let [session (Session/getInstance (smtp-properties smtp))
                       msg (build-message session from to (.getName file) file)]
                   (send-message! msg from password)
                   true)
                 (catch Exception e
                   (println " - email FAILED to" to "-" (.getMessage e))
                   false)))
      (println " - email sent to " to))))

;; ---------- IMAP worklog reading ----------

(defn imap-store
  "Open and connect an IMAPS Store. Caller is responsible for closing."
  ^Store [host user password]
  (let [props (doto (Properties.) (.put "mail.store.protocol" "imaps"))
        session (Session/getInstance props)
        store (.getStore session "imaps")]
    (.connect store host user password)
    store))

(defn- open-folder ^Folder [^Store store folder-name]
  (doto (.getFolder store ^String folder-name)
    (.open Folder/READ_ONLY)))

(defn- search-term ^SearchTerm [subject from]
  (let [terms (cond-> []
                subject (conj (SubjectTerm. ^String subject))
                from    (conj (FromStringTerm. ^String from)))]
    (case (count terms)
      0 nil
      1 (first terms)
      (AndTerm. (into-array SearchTerm terms)))))

(defn- message-subject [^jakarta.mail.Message m]
  (or (.getSubject m) ""))

(defn- message-from-address [^jakarta.mail.Message m]
  (when-let [addrs (seq (.getFrom m))]
    (str (first addrs))))

(defn- text-from-part [^Part part]
  (let [content (.getContent part)]
    (cond
      (instance? String content) content
      (instance? Multipart content)
      (let [mp ^Multipart content]
        (->> (range (.getCount mp))
             (map #(.getBodyPart mp %))
             (some (fn [^Part bp]
                     (when (.isMimeType bp "text/*")
                       (str (.getContent bp)))))))
      :else (str content))))

(defn message-content [^jakarta.mail.Message m]
  (or (text-from-part m) ""))

(defn server-find-messages
  "Find messages in the given folder, server-side filtered by subject / from
  (either may be nil)."
  [^Store store folder-name subject from]
  (let [folder (open-folder store folder-name)]
    (if-let [term (search-term subject from)]
      (into [] (.search folder term))
      (into [] (.getMessages folder)))))

(defn manual-find-messages
  "Fallback: fetch every message in the folder and filter in-process. Slow;
  used when the server-side search returns nothing and :try-manual is set."
  [^Store store folder-name subject from]
  (let [folder (open-folder store folder-name)
        match-subject (if subject
                        #(str/includes? (message-subject %) subject)
                        (constantly true))
        match-from (if from
                     #(str/includes? (or (message-from-address %) "") from)
                     (constantly true))]
    (->> (.getMessages folder)
         (filter #(and (match-subject %) (match-from %)))
         (into []))))

(defn find-messages
  "Get all messages for the given month from the given imap server."
  [month {host :host user :user password :pass try-manual :try-manual
          folder :folder from :from subject :subject}]
  (let [store (imap-store host user password)
        subject (time/format-month subject month)
        messages (server-find-messages store folder subject from)]
    (if (and (empty? messages) try-manual)
      (manual-find-messages store folder subject from)
      messages)))

(defn split-cells [content]
  (->> content
       str/split-lines
       (remove str/blank?)
       (map #(str/split % #"[\s;]+"))))

(defn zip-item [headers cell]
  (into (sorted-map) (map vector headers cell)))

(defn parse-double [s]
  (Double. (re-find #"-?[\d\.]+" s)))

(defn extract-items [headers message]
  (->> message
       message-content
       split-cells
       (map (partial zip-item headers))
       (map #(update % :worked parse-double))))

(defn get-worklogs
  "Get all worklogs for the given month from the given imap server."
  [month imap]
  (->> imap (find-messages month) (map (partial extract-items (:headers imap))) flatten))
