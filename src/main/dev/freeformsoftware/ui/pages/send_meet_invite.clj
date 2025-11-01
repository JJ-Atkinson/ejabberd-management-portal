(ns dev.freeformsoftware.ui.pages.send-meet-invite
  "Page for creating and sending Jitsi meeting invitations to group members.
   
   Uses JWT authentication similar to signup.clj - requires a special JWT token
   to access this page."
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.string :as str]
   [dev.freeformsoftware.db.file-interaction :as file-db]
   [dev.freeformsoftware.ejabberd.admin-bot :as admin-bot]
   [dev.freeformsoftware.ejabberd.admin-bot-actions :as bot-actions]
   [dev.freeformsoftware.link-provider :as link-provider]
   [dev.freeformsoftware.ui.html-fragments :as ui.frag]
   [hiccup2.core :as h]
   [taoensso.telemere :as tel]))

(set! *warn-on-reflection* true)

(defn send-invite-form
  "Meeting invitation form for sending Jitsi links to group members."
  [conf groups error success?]
  (ui.frag/html-body
   conf
   ;; Custom background with min-h-screen for scrollable content
   [:div.bg-orange-50.flex.flex-row.items-stretch.min-h-screen.relative
    [:div.flex.justify-center.w-screen.py-8.px-4
     [:div.md:border-2.p-8.max-w-2xl.w-full
      [:form {:method "POST" :action "/send-meet-invite"}
       (ui.frag/form
        ;; Title
        [:div
         [:h1.text-2xl.font-bold.mb-4 "Send Meeting Invitations"]
         [:p.text-lg.text-gray-700.mb-6
          "Create a Jitsi meeting and send invitation links to selected groups"]
         (when success?
           [:div.bg-green-100.border.border-green-400.text-green-700.px-4.py-3.rounded.mb-4
            [:p "Meeting invitations sent successfully!"]])]

        ;; Fields
        [:div.flex.flex-col.gap-6
         ;; Meeting name field
         (ui.frag/form-input {:type "text"
                              :id "meeting-name"
                              :name "meeting-name"
                              :label "Meeting Name"
                              :placeholder "Enter meeting name (optional)"
                              :class ui.frag/input-classes})

         ;; Admin Groups section
         [:div
          [:label.font-semibold.mb-2.block "Admin Groups (will have moderator privileges)"]
          [:p.text-sm.text-gray-600.mb-3 "Members of these groups will join as moderators"]
          [:div.flex.flex-col.gap-2.border.p-4.rounded
           (for [[group-key group-name] (sort-by second groups)]
             [:label.flex.items-center.gap-2.cursor-pointer
              [:input
               {:type "checkbox"
                :name "admin-groups[]"
                :value (subs (str group-key) 1)
                :class ui.frag/checkbox-classes}]
              [:span group-name]])]]

         ;; Also Invited section
         [:div
          [:label.font-semibold.mb-2.block "Also Invited (regular participants)"]
          [:p.text-sm.text-gray-600.mb-3 "Members of these groups will join as regular participants"]
          [:div.flex.flex-col.gap-2.border.p-4.rounded
           (for [[group-key group-name] (sort-by second groups)]
             [:label.flex.items-center.gap-2.cursor-pointer
              [:input
               {:type "checkbox"
                :name "invited-groups[]"
                :value (subs (str group-key) 1)
                :class ui.frag/checkbox-classes}]
              [:span group-name]])]]

         [:div.mt-4.text-sm.text-gray-600
          [:p "Note: Invitation links will be sent via XMPP to all members of the selected groups."]]]

        ;; Button bar
        (ui.frag/right-aligned
         [:button
          {:type "submit"
           :class ui.frag/button-classes}
          "Send Invites"])

        ;; Error (4th optional argument)
        error)]]]
    [:div#modal-container.absolute]]))

(defn parse-invite-form-params
  "Parses form parameters for meeting invitations."
  [{:keys [meeting-name admin-groups invited-groups]}]
  (let [admin-groups-set (cond
                           (nil? admin-groups) #{}
                           (string? admin-groups) #{(keyword admin-groups)}
                           :else (set (map keyword admin-groups)))
        invited-groups-set (cond
                             (nil? invited-groups) #{}
                             (string? invited-groups) #{(keyword invited-groups)}
                             :else (set (map keyword invited-groups)))]
    {:meeting-name meeting-name
     :admin-groups admin-groups-set
     :invited-groups invited-groups-set}))

(defn send-meeting-invitations!
  "Sends meeting invitations to all members of the specified groups.
   
   Parameters:
   - conf: Configuration map with :user-db, :link-provider, :admin-bot, :xmpp-domain
   - meeting-name: Name of the meeting (optional, will generate if nil)
   - admin-groups: Set of group keywords that should receive moderator privileges
   - invited-groups: Set of group keywords for regular participants"
  [{:keys [user-db link-provider admin-bot xmpp-domain]} meeting-name admin-groups invited-groups]
  (try
    (let [db (file-db/read-user-db user-db)
          all-members (:members db)

          ;; Generate meeting name if not provided
          meeting-name (str/trim (or meeting-name ""))
          final-meet-name (if (not= "" meeting-name)
                            (csk/->PascalCase meeting-name)
                            (str "Meet-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

          ;; Filter members by group membership
          admin-members (filter (fn [member]
                                  (some admin-groups (:groups member)))
                                all-members)
          invited-members (filter (fn [member]
                                    (some invited-groups (:groups member)))
                                  all-members)

          ;; Combine and deduplicate (admins take precedence)
          admin-user-ids (set (map :user-id admin-members))
          all-recipients (concat admin-members
                                 (remove #(admin-user-ids (:user-id %))
                                         invited-members))

          ;; Create send-message function wrapper (similar to admin-bot/handle-dm!)
          send-message-fn (partial admin-bot/send-message! admin-bot)]

      (tel/log! :info
                ["Sending meeting invitations"
                 {:meeting-name final-meet-name
                  :admin-count (count admin-members)
                  :invited-count (count invited-members)
                  :total-recipients (count all-recipients)}])

      ;; Use shared function to send invitations
      (let [sent-count (bot-actions/send-jitsi-invitations!
                        send-message-fn
                        link-provider
                        final-meet-name
                        all-recipients
                        #(admin-user-ids (:user-id %))
                        xmpp-domain)]

        {:success? true
         :meeting-name final-meet-name
         :recipients-count sent-count}))

    (catch Exception e
      (tel/log! :error ["Failed to send meeting invitations" {:error (ex-message e)}])
      {:success? false
       :error (ex-message e)})))

(defn handle-send-invite-post
  "Handles POST request for sending meeting invitations."
  [conf request]
  (let [params (:params request)
        {:keys [meeting-name admin-groups invited-groups]} (parse-invite-form-params params)
        user-db (file-db/read-user-db (:user-db conf))
        groups (:groups user-db)]

    (cond
      (and (empty? admin-groups) (empty? invited-groups))
      {:status 400
       :headers {"Content-Type" "text/html"}
       :body (str (h/html
                   (send-invite-form conf groups
                                     "Please select at least one group to invite."
                                     false)))}

      :else
      (let [result (send-meeting-invitations! conf meeting-name admin-groups invited-groups)]
        (if (:success? result)
          {:status 200
           :headers {"Content-Type" "text/html"}
           :body (str (h/html
                       (send-invite-form conf groups nil true)))}
          {:status 500
           :headers {"Content-Type" "text/html"}
           :body (str (h/html
                       (send-invite-form conf groups
                                         (str "Failed to send invitations: " (:error result))
                                         false)))})))))

(defn routes
  [conf]
  {"GET /send-meet-invite" (fn [request]
                             (let [user-db (file-db/read-user-db (:user-db conf))
                                   groups (:groups user-db)]
                               {:status 200
                                :headers {"Content-Type" "text/html"}
                                :body (str (h/html (send-invite-form conf groups nil false)))}))

   "POST /send-meet-invite" (partial handle-send-invite-post conf)})
