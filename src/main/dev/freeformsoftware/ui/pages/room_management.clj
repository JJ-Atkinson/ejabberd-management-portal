(ns dev.freeformsoftware.ui.pages.room-management
  (:require
   [clojure.string :as str]
   [dev.freeformsoftware.db.user-db :as file-db]
   [dev.freeformsoftware.ejabberd.sync-state :as sync-state]
   [dev.freeformsoftware.ui.html-fragments :as ui.frag]
   [hiccup2.core :as h]
   [ring.util.response :as response]
   [zprint.core :as zp]))

(set! *warn-on-reflection* true)

(defn room-form-fragment
  "Form for creating or editing a room. Posts to endpoint that returns the same form with success flag.
   Caller is responsible for wrapping in a form element."
  [conf {:keys [room edit? success? error error-value form-id]}]
  (let [user-db (file-db/read-user-db (:user-db conf))
        groups (:groups user-db)
        room-members (set (:members room))
        room-admins (set (:admins room))
        available-groups (sort-by (fn [[k v]] v) groups)
        fragment-id (or form-id "room-form-fragment")]
    (h/html
     [:div {:id fragment-id}
      ;; Hidden notification for processing state
      [:div.processing-notification.hidden.bg-blue-100.border.border-blue-400.text-blue-700.px-4.py-3.rounded.mb-4
       [:p "Processing... Please wait."]]

      (when success?
        [:div.bg-green-100.border.border-green-400.text-green-700.px-4.py-3.rounded.mb-4
         [:p (if edit? "Room updated successfully!" "Room created successfully!")]])

      (when error
        [:div.bg-red-100.border.border-red-400.text-red-700.px-4.py-3.rounded.mb-4
         [:p (str error)]
         (when error-value
           [:pre.mt-2.text-xs.overflow-x-auto
            (zp/zprint-str error-value {:map {:hang? false :force-nl? true}})])])

      ;; Hidden field for room-id when editing
      (when edit?
        [:input {:type "hidden" :name "room-id" :value (:room-id room)}])

      [:div.flex.flex-col.gap-4
       ;; Room name field (editable)
       (ui.frag/form-input {:type "text"
                            :id "room-name"
                            :name "room-name"
                            :label "Room Name"
                            :placeholder "Enter room name"
                            :value (:name room)
                            :class ui.frag/input-classes
                            :required true})

       ;; Room ID field (read-only, shown only when editing)
       (when edit?
         (ui.frag/form-input {:type "text"
                              :id "room-id-display"
                              :name "room-id-display"
                              :label "Room ID"
                              :value (:room-id room)
                              :class ui.frag/input-classes
                              :disabled true}))

       [:div
        [:label.flex.items-center.gap-2.cursor-pointer
         [:input
          {:type "checkbox"
           :name "only-admins-can-speak"
           :checked (:only-admins-can-speak? room)
           :class ui.frag/checkbox-classes}]
         [:span.font-semibold "Only admins can speak (moderated)"]]]

       ;; Admin Groups section
       [:div
        [:label.font-semibold.mb-2.block "Admin Groups"]
        [:div.flex.flex-col.gap-2
         (for [[group-key group-name] available-groups]
           [:label.flex.items-center.gap-2.cursor-pointer
            [:input
             {:type "checkbox"
              :name "admins[]"
              :value (subs (str group-key) 1)
              :checked (contains? room-admins group-key)
              :class ui.frag/checkbox-classes}]
            [:span group-name]])]]

       ;; Member Groups section
       [:div
        [:label.font-semibold.mb-2.block "Member Groups"]
        [:div.flex.flex-col.gap-2
         (for [[group-key group-name] available-groups]
           [:label.flex.items-center.gap-2.cursor-pointer
            [:input
             {:type "checkbox"
              :name "members[]"
              :value (subs (str group-key) 1)
              :checked (contains? room-members group-key)
              :class ui.frag/checkbox-classes}]
            [:span group-name]])]]]])))

(defn room-panel-fragment
  [{:keys [user-db sync-state] :as conf} selected-room-id]
  (let [user-db (file-db/read-user-db user-db)
        rooms (:rooms user-db)
        room-info (when selected-room-id
                    (first (filter #(= (:room-id %) selected-room-id)
                                   rooms)))
        lock-state (sync-state/read-lock-state sync-state)
        locked? (:locked? lock-state)
        lock-reason (:reason lock-state)]
    (h/html
     [:div#room-panel.flex.flex-col.md:flex-row.md:border.md:border-slate-300
      [:div.md:border-r-2.md:border-r-slate-900.md:w-64.overflow-y-auto
       {:class "max-h-[50vh] md:max-h-none"}
       [:ul.list-none.p-0.m-0
        (for [room rooms]
          (let [room-id (:room-id room)
                room-name (:name room)]
            [:li
             [:div.flex-grow
              {:class (if (= room-id selected-room-id)
                        ui.frag/selected-link-classes
                        ui.frag/clickable-link-classes)
               :hx-get (str "/frags/pages/all-rooms-panel/room/" room-id)
               :hx-target "#room-panel"
               :hx-swap "outerHTML"
               :hx-push-url (str "/pages/rooms/" room-id)}
              [:span room-name]]]))]]
      [:div.mt-6.md:mt-0.md:ml-6.flex-grow
       (when selected-room-id
         (if room-info
           [:form
            [:div.flex.flex-col.p-4.gap-4
             (room-form-fragment conf
                                 {:room room-info
                                  :edit? true
                                  :success? false
                                  :error nil
                                  :form-id "room-form-fragment-edit"})
             (ui.frag/right-aligned
              [:div.flex.gap-2
               ;; Delete button - wrap with tooltip if locked
               (if locked?
                 (ui.frag/tooltip
                  [:button
                   {:class (conj ui.frag/delete-button-classes "opacity-50" "cursor-not-allowed" "pointer-events-none")
                    :disabled true}
                   "Delete"]
                  (str "Cannot delete: " lock-reason)
                  ["inline-block" "cursor-help"])
                 [:button
                  {:class (conj ui.frag/delete-button-classes "disabling-action-button")
                   :hx-target "body"
                   :hx-post (str "/actions/pages/all-room-panel/delete-room/" (:room-id room-info))
                   :hx-confirm (str "Are you sure you want to delete room " (:name room-info))
                   :_ (ui.frag/disable-action-buttons-on-click-script "room-form-fragment-edit")}
                  "Delete"])

               ;; Apply button - wrap with tooltip if locked
               (if locked?
                 (ui.frag/tooltip
                  [:button
                   {:class (conj ui.frag/button-classes "opacity-50" "cursor-not-allowed" "pointer-events-none")
                    :disabled true}
                   "Apply"]
                  (str "Cannot apply changes: " lock-reason)
                  ["inline-block" "cursor-help"])
                 [:button
                  {:type "submit"
                   :class (conj ui.frag/button-classes "disabling-action-button")
                   :hx-post "/actions/pages/all-room-panel/submit-room"
                   :hx-target "#room-form-fragment-edit"
                   :hx-swap "outerHTML"
                   :_ (ui.frag/disable-action-buttons-on-click-script "room-form-fragment-edit")}
                  "Apply"])])]]
           [:div.p-4
            [:p "Room " selected-room-id " not found"]]))]])))

(defn rooms-list-page
  [conf {[room] :path-params :as request}]
  [:div.m-10.mx-auto.max-w-screen-lg.md:m-10
   [:h1.text-3xl.font-bold.mb-6.text-center.md:text-left "Rooms"]
   (room-panel-fragment conf room)])

(defn create-room-fragment
  [conf req]
  (let [lock-state (sync-state/read-lock-state (:sync-state conf))
        locked? (:locked? lock-state)
        lock-reason (:reason lock-state)]
    (h/html
     (ui.frag/modal-container
      [:form
       (ui.frag/form-modal
        [:h1.text-2xl.font-bold.mb-4 "Create a new room"]
        (room-form-fragment conf
                            {:room {}
                             :edit? false
                             :form-id "room-form-fragment-create"})
        (ui.frag/right-aligned
         [:div.flex.gap-2
          [:button
           {:class ui.frag/cancel-button-classes
            :_ "on click trigger closeModal"}
           "Cancel"]

          ;; Submit button - wrap with tooltip if locked
          (if locked?
            (ui.frag/tooltip
             [:button
              {:class (conj ui.frag/button-classes "opacity-50" "cursor-not-allowed" "pointer-events-none")
               :disabled true}
              "Submit"]
             (str "Cannot create room: " lock-reason)
             ["inline-block" "cursor-help"])
            [:button
             {:type "submit"
              :class (conj ui.frag/button-classes "disabling-action-button")
              :hx-post "/actions/pages/all-room-panel/submit-room"
              :hx-target "#room-form-fragment-create"
              :hx-swap "outerHTML"
              :_ (ui.frag/disable-action-buttons-on-click-script "room-form-fragment-create")}
             "Submit"])]))]))))

(defn respond-create-room-fragment
  [conf req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (str (create-room-fragment conf req))})

(defn action-button-fragment
  [conf req]
  (let [lock-state (sync-state/read-lock-state (:sync-state conf))
        locked? (:locked? lock-state)
        lock-reason (:reason lock-state)]
    (if locked?
      (ui.frag/tooltip
       [:span
        {:class (conj ui.frag/clickable-link-classes " opacity-50 cursor-not-allowed w-full")}
        "+ Add a new room"]
       (str "Cannot add room: " lock-reason)
       "w-full")
      [:a
       {:class ui.frag/clickable-link-classes
        :hx-get "/frags/pages/all-rooms-panel/create-room"
        :hx-target "#modal-container"}
       "+ Add a new room"])))

(defn parse-room-form-params
  [{:keys [members admins room-name room-id only-admins-can-speak]}]
  (let [members-set (cond
                      (nil? members) #{}
                      (string? members) #{(keyword members)}
                      :else (set (map keyword members)))
        admins-set (cond
                     (nil? admins) #{}
                     (string? admins) #{(keyword admins)}
                     :else (set (map keyword admins)))]
    {:room-def (cond-> {:name (str/trim room-name)
                        :members members-set
                        :admins admins-set
                        :only-admins-can-speak? (= "on" only-admins-can-speak)}
                 room-id (assoc :room-id room-id))
     :create? (not (boolean room-id))}))

(defn swap-room
  [user-db {:keys [room-def create?]}]
  (update user-db
          :rooms
          (fn [rooms]
            (vec
             (if create?
               (conj rooms room-def)
               (map (fn [{:keys [room-id] :as existing}]
                      (if (= room-id (:room-id room-def))
                        room-def
                        existing))
                    rooms))))))

(defn respond-submit-room
  "This is called in 2 scenarios: edit, and create. 
   On edit, it should return room-form-fragment in all cases. 
   On create, if there are no errors we should redirect to room-id. If there are errors,
   we should return the room-form-fragment with the errors listed."
  [{:keys [sync-state] :as conf} {:keys [params]}]
  (let [{:keys [create? room-def] :as params} (parse-room-form-params params)
        {:keys [room-id]} room-def
        reason (if create?
                 (str "Creating new room: " (:name room-def))
                 (str "Updating room: " (:name room-def)))
        {:keys [success? errors error-value] :as ss} (sync-state/swap-state!
                                                      sync-state
                                                      {:reason reason}
                                                      #(swap-room % params))
        form-id (if create? "room-form-fragment-create" "room-form-fragment-edit")]

    (cond
      success?
      {:status 200
       :headers {"HX-Redirect" (str "/pages/rooms/" room-id)}}

      :else
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (str (room-form-fragment conf
                                      {:room room-def
                                       :edit? (not create?)
                                       :success? success?
                                       :error errors
                                       :error-value error-value
                                       :form-id form-id}))})))

(defn respond-delete-room
  [{:keys [sync-state]} {[room-id] :path-params}]
  (sync-state/swap-state!
   sync-state
   {:reason (str "Deleting room: " room-id)}
   (fn [user-db]
     (update user-db
             :rooms
             (fn [rooms]
               (vec
                (remove #(= room-id (:room-id %)) rooms))))))
  {:status 200
   :headers {"HX-Redirect" "/pages/rooms"}})

(defn routes
  [conf]
  {"GET /frags/pages/all-rooms-panel/room/*" (fn [{[selected-room] :path-params :as request}]
                                               {:status 200
                                                :headers {"Content-Type" "text/html"}
                                                :body (str (room-panel-fragment conf selected-room))})
   "GET /frags/pages/all-rooms-panel/create-room" (partial respond-create-room-fragment conf)
   "POST /actions/pages/all-room-panel/submit-room" (partial respond-submit-room conf)
   "POST /actions/pages/all-room-panel/delete-room/*" (partial respond-delete-room conf)})
