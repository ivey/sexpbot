(ns sexpbot.plugins.mail
  (:refer-clojure :exclude [extend])
  (:require [irclj.irclj :as ircb])
  (:use [sexpbot respond info]
	[clj-time core format]
        [somnium.congomongo :only [fetch fetch-one insert! destroy!]]))

(def alerted (atom {}))

(defn new-message [from to text]
  (let [time (unparse (formatters :date-time-no-ms) (now))] 
    (insert! :mail {:to to
                    :from from 
                    :message text
                    :timestamp time})))

(defn compose-message [{:keys [from message timestamp]}]
  (str "From: " from ", Time: " timestamp ", Text: " message))

(defn fetch-messages [user]
  (let [mlist (doall (map compose-message (fetch :mail :where {:to user})))]
    (destroy! :mail {:to user})
    mlist))

(defn count-messages [user]
  (count (fetch :mail :where {:to user})))

(defn alert-time? [user]
  (if-let [usertime (@alerted (.toLowerCase user))]
    (< 300 (-> usertime (interval (now)) in-secs))
    true))

(defn mail-alert
  [{:keys [irc channel nick]}]
  (let [lower-nick (.toLowerCase nick)
	nmess (count-messages lower-nick)]
    (when (and (> nmess 0) (alert-time? lower-nick))
      (ircb/send-notice irc nick (str nick ": You have " nmess 
				      " new message(s). Try the mymail or mail commands without any arguments to see them. This also works via PM."))
      (swap! alerted assoc lower-nick (now)))))

(defn get-messages [irc bot nick]
  (let [lower-nick (.toLowerCase nick)]
    (if-let [messages (seq (fetch-messages lower-nick))]
      (doseq [message messages] (send-message irc bot lower-nick message))
      (send-message irc bot nick "You have no messages."))))

(defplugin
  (:hook :on-message (fn [irc-map] (mail-alert irc-map)))
  (:hook :on-join (fn [irc-map] (mail-alert irc-map)))
  
  (:cmd 
   "Request that your messages be sent you via PM. Executing this command will delete all your messages."
   #{"getmessages" "getmail" "mymail"} 
   (fn [{:keys [irc bot nick]}]
     (get-messages irc bot nick)))

  (:cmd 
   "Send somebody a message. Takes a nickname and a message to send. Will alert the person with a notice."
   #{"mail"}
   (fn [{:keys [irc bot channel nick args irc]}]
     (if (seq args)
       (let [lower-user (.toLowerCase (first args))]
         (if (and (not (.contains lower-user "serv"))
                  (not= lower-user (.toLowerCase (:name @irc))))
           (do
             (new-message nick lower-user 
                          (->> args rest (interpose " ") (apply str)))
             (send-message irc bot channel "Message saved."))
           (send-message irc bot channel "You can't message the unmessageable.")))
       (get-messages irc bot nick)))))
