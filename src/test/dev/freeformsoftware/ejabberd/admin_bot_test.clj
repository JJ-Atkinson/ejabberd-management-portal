(ns dev.freeformsoftware.ejabberd.admin-bot-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.freeformsoftware.db.user-db :as file-db]
   [dev.freeformsoftware.ejabberd.admin-bot :as admin-bot]
   [dev.freeformsoftware.ejabberd.ejabberd-api :as api]
   [dev.freeformsoftware.ejabberd.sync-state :as sync-state]))

(deftest create-rooms-only-joins-bot-enabled-rooms
  (let [joined-rooms (atom [])
        created-rooms (atom [])
        rooms [{:name "General"
                :members #{:group/member}
                :admins #{:group/owner}
                :only-admins-can-speak? false}
               {:name "Bot Commands"
                :members #{:group/bot :group/member}
                :admins #{:group/owner}
                :only-admins-can-speak? false}]]
    (with-redefs [api/create-room-with-opts (fn [_ room-id _ _]
                                             (swap! created-rooms conj room-id))
                  admin-bot/join-room-if-new! (fn [_ room-id]
                                                (swap! joined-rooms conj room-id))]
      (#'sync-state/create-rooms {} rooms {} "conference.example.org" {:admin-bot true})
      (is (= ["general" "bot-commands"] @created-rooms))
      (is (= ["bot-commands"] @joined-rooms)))))

(deftest join-all-rooms-follows-bot-group-attachment
  (let [joined-rooms (atom [])
        left-rooms (atom [])
        listeners (atom {"general@conference.example.org" {:muc :general-muc}
                         "commands@conference.example.org" {:muc :commands-muc}})
        db {:rooms [{:name "General"
                     :room-id "general"
                     :members #{:group/member}
                     :admins #{:group/owner}
                     :only-admins-can-speak? false}
                    {:name "Commands"
                     :room-id "commands"
                     :members #{:group/bot :group/member}
                     :admins #{:group/owner}
                     :only-admins-can-speak? false}
                    {:name "New Bot Room"
                     :room-id "new-bot-room"
                     :members #{:group/member}
                     :admins #{:group/bot :group/owner}
                     :only-admins-can-speak? false}]}]
    (with-redefs [file-db/read-user-db (constantly db)
                  admin-bot/join-muc-room (fn [conf room-id]
                                            (swap! joined-rooms conj room-id)
                                            (let [muc (keyword (str room-id "-muc"))]
                                              (swap! (:muc-room-listeners conf)
                                                     assoc
                                                     (str room-id "@" (:muc-service conf))
                                                     {:muc muc})
                                              muc))
                  admin-bot/leave-muc-room (fn [room-id muc]
                                             (swap! left-rooms conj [room-id muc]))]
      (#'admin-bot/join-all-rooms {:user-db :user-db
                                   :muc-service "conference.example.org"
                                   :connection :connection
                                   :muc-room-listeners listeners})
      (is (= [["general" :general-muc]] @left-rooms))
      (is (= ["new-bot-room"] @joined-rooms))
      (is (contains? @listeners "commands@conference.example.org"))
      (is (contains? @listeners "new-bot-room@conference.example.org"))
      (is (not (contains? @listeners "general@conference.example.org"))))))
