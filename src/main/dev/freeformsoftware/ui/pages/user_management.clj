(ns dev.freeformsoftware.ui.pages.user-management
  (:require
   [dev.freeformsoftware.link-provider :as link-provider]
   [dev.freeformsoftware.db.user-db :as file-db]
   [dev.freeformsoftware.ejabberd.sync-state :as sync-state]
   [dev.freeformsoftware.ui.html-fragments :as ui.frag]
   [hiccup2.core :as h]
   [ring.util.response :as resp]
   [zprint.core :as zp]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn create-password-reset-link
  [conf username user-id]
  (let [reset-url (link-provider/create-password-reset-url
                   (:link-provider conf)
                   user-id
                   username
                   :duration-hours 24)]
    [:div.flex.items-center.gap-2
     [:input
      {:type "text"
       :readonly true
       :value reset-url
       :id (str "reset-link-" user-id)
       :class (str (clojure.string/join " " ui.frag/input-classes)
                   " cursor-pointer text-gray-600")
       :style "max-width: 50%; background-color: rgba(239, 239, 239, 0.3);"
       :onclick "this.select()"}]
     [:button
      {:type "button"
       :class ui.frag/button-classes
       :_ (str "on click "
               "get #reset-link-" user-id " "
               "call its.select() "
               "call document.execCommand('copy') "
               "set my.textContent to 'Copied!' "
               "wait 2s "
               "set my.textContent to 'Copy'")}
      "Copy"]]))

(defn user-form-fragment
  "Form for creating or editing a user. Posts to endpoint that returns the same form with success flag.
   Caller is responsible for wrapping in a form element."
  [conf {:keys [user edit? success? error error-value form-id]}]
  (let [user-db (file-db/read-user-db (:user-db conf))
        groups (:groups user-db)
        user-groups (set (:groups user))
        available-groups (sort-by (fn [[k v]] v) groups)
        fragment-id (or form-id "user-form-fragment")]
    (h/html
     [:div {:id fragment-id}
      ;; Hidden notification for processing state
      [:div.processing-notification.hidden.bg-blue-100.border.border-blue-400.text-blue-700.px-4.py-3.rounded.mb-4
       [:p "Processing... Please wait."]]

      (when success?
        [:div.bg-green-100.border.border-green-400.text-green-700.px-4.py-3.rounded.mb-4
         [:p (if edit? "User updated successfully!" "User created successfully!")]])

      (when error
        [:div.bg-red-100.border.border-red-400.text-red-700.px-4.py-3.rounded.mb-4
         [:p (str error)]
         (when error-value
           [:pre.mt-2.text-xs.overflow-x-auto
            (zp/zprint-str error-value {:map {:hang? false :force-nl? true}})])])

      ;; Hidden field for old user-id when editing
      (when edit?
        [:input {:type "hidden" :name "old-user-id" :value (:user-id user)}])

      [:div.flex.flex-col.gap-4
       ;; Name field (first)
       (ui.frag/form-input {:type "text"
                            :id "name"
                            :name "name"
                            :label "Name"
                            :placeholder "Enter full name"
                            :value (:name user)
                            :class ui.frag/input-classes
                            :required true})

       ;; User ID field (second)
       (ui.frag/form-input {:type "text"
                            :id "user-id"
                            :name "user-id"
                            :label "User ID"
                            :placeholder "Enter user ID"
                            :value (:user-id user)
                            :class (str (clojure.string/join " " ui.frag/input-classes)
                                        (when edit? " cursor-pointer"))
                            :disabled edit?
                            :onclick (when edit? "this.select()")
                            :required true})

       ;; Groups section
       [:div
        [:label.font-semibold.mb-2.block "Groups"]
        [:div.flex.flex-col.gap-2
         (for [[group-key group-name] available-groups]
           [:label.flex.items-center.gap-2.cursor-pointer
            [:input
             {:type "checkbox"
              :name "groups[]"
              :value (subs (str group-key) 1)
              :checked (contains? user-groups group-key)
              :class ui.frag/checkbox-classes}]
            [:span group-name]])]]

       (when edit?
         (create-password-reset-link conf (:name user) (:user-id user)))]])))

(defn user-panel-fragment
  [{:keys [user-db sync-state] :as conf} selected-user]
  (let [user-db (file-db/read-user-db user-db)
        members (:members user-db)
        user-info (when selected-user
                    (first (filter #(= (:user-id %) selected-user)
                                   members)))
        lock-state (sync-state/read-lock-state sync-state)
        locked? (:locked? lock-state)
        lock-reason (:reason lock-state)]
    (h/html
     [:div#user-panel.flex.flex-col.md:flex-row.md:border.md:border-slate-300
      [:div.md:border-r-2.md:border-r-slate-900.md:w-64.overflow-y-auto
       {:class "max-h-[50vh] md:max-h-none"}
       [:ul.list-none.p-0.m-0
        (for [member members]
          (let [user-id (:user-id member)]
            [:li
             [:div.flex-grow
              {:class (if (= user-id selected-user)
                        ui.frag/selected-link-classes
                        ui.frag/clickable-link-classes)
               :hx-get (str "/frags/pages/all-users-panel/user/" user-id)
               :hx-target "#user-panel"
               :hx-swap "outerHTML"
               :hx-push-url (str "/pages/users/" user-id)}
              [:span (:name member) " (" user-id ")"]]]))]]
      [:div.mt-6.md:mt-0.md:ml-6.flex-grow
       (when selected-user
         (if user-info
           [:form
            [:div.flex.flex-col.p-4.gap-4
             (user-form-fragment conf
                                 {:user user-info
                                  :edit? true
                                  :success? false
                                  :error nil
                                  :form-id "user-form-fragment-edit"})
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
                   :hx-post (str "/actions/pages/all-user-panel/delete-user/" (:user-id user-info))
                   :hx-confirm (str "Are you sure you want to delete user " (:user-id user-info))
                   :_ (ui.frag/disable-action-buttons-on-click-script "user-form-fragment-edit")}
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
                   :hx-post "/actions/pages/all-user-panel/submit-user"
                   :hx-target "#user-form-fragment-edit"
                   :hx-swap "outerHTML"
                   :_ (ui.frag/disable-action-buttons-on-click-script "user-form-fragment-edit")}
                  "Apply"])])]]
           [:div.p-4
            [:p "User " selected-user " not found"]]))]])))

(defn users-list-page
  [conf {[user] :path-params :as request}]
  [:div.m-10.mx-auto.max-w-screen-lg.md:m-10
   [:h1.text-3xl.font-bold.mb-6.text-center.md:text-left "Registered Users"]
   (user-panel-fragment conf user)])

(defn create-user-fragment
  [conf req]
  (let [lock-state (sync-state/read-lock-state (:sync-state conf))
        locked? (:locked? lock-state)
        lock-reason (:reason lock-state)]
    (h/html
     (ui.frag/modal-container
      [:form
       (ui.frag/form-modal
        [:h1.text-2xl.font-bold.mb-4 "Create a new user"]
        (user-form-fragment conf
                            {:user {}
                             :edit? false
                             :form-id "user-form-fragment-create"})
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
             (str "Cannot create user: " lock-reason)
             ["inline-block" "cursor-help"])
            [:button
             {:type "submit"
              :class (conj ui.frag/button-classes "disabling-action-button")
              :hx-post "/actions/pages/all-user-panel/submit-user"
              :hx-target "#user-form-fragment-create"
              :hx-swap "outerHTML"
              :_ (ui.frag/disable-action-buttons-on-click-script "user-form-fragment-create")}
             "Submit"])]))]))))

(defn respond-create-user-fragment
  [conf req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (str (create-user-fragment conf req))})

(defn action-button-fragment
  [conf req]
  (let [lock-state (sync-state/read-lock-state (:sync-state conf))
        locked? (:locked? lock-state)
        lock-reason (:reason lock-state)]
    (if locked?
      (ui.frag/tooltip
       [:span
        {:class (conj ui.frag/clickable-link-classes " opacity-50 cursor-not-allowed w-full")}
        "+ Add a new user"]
       (str "Cannot add user: " lock-reason)
       "w-full")
      [:a
       {:class ui.frag/clickable-link-classes
        :hx-get "/frags/pages/all-users-panel/create-user"
        :hx-target "#modal-container"}
       "+ Add a new user"])))

(defn parse-user-form-params
  [{:keys [groups name user-id old-user-id]}]
  {:member-def {:name (str/trim name)
                :user-id (str/trim (or old-user-id user-id))
                :groups (set (map keyword groups))}
   :create? (not (boolean old-user-id))})

(defn swap-user
  [user-db {:keys [member-def create?]}]
  (update user-db
          :members
          (fn [members]
            (vec
             (if create?
               (conj members member-def)
               (map (fn [{:keys [user-id] :as existing}]
                      (if (= user-id (:user-id member-def))
                        member-def
                        existing))
                    members))))))

(defn respond-submit-user
  "This is called in 2 scenarios: edit, and create. 
   On edit, it should return user-form-fragment in all cases. 
   On create, if there are no errors we should redirect to user-id. If there are errors,
   we should return the user-form-fragment with the errors listed."
  [{:keys [sync-state] :as conf} {:keys [params]}]
  (let [{:keys [create? member-def] :as params} (parse-user-form-params params)
        {:keys [user-id]} member-def
        reason (if create?
                 (str "Creating new user: " user-id)
                 (str "Updating user: " user-id))
        {:keys [success? errors error-value]} (sync-state/swap-state!
                                               sync-state
                                               {:reason reason}
                                               #(swap-user % params))
        form-id (if create? "user-form-fragment-create" "user-form-fragment-edit")]

    (cond
      success?
      {:status 200
       :headers {"HX-Redirect" (str "/pages/users/" user-id)}}

      :else
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (str (user-form-fragment conf
                                      {:user member-def
                                       :edit? (not create?)
                                       :success? success?
                                       :error errors
                                       :error-value error-value
                                       :form-id form-id}))})))

(defn respond-delete-user
  [{:keys [sync-state]} {[user-id] :path-params}]
  (sync-state/swap-state!
   sync-state
   {:reason (str "Deleting user: " user-id)}
   (fn [user-db]
     (update user-db
             :members
             (fn [members]
               (vec
                (remove #(= user-id (:user-id %)) members))))))
  {:status 200
   :headers {"HX-Redirect" "/pages/users"}})

(defn routes
  [conf]
  {"GET /frags/pages/all-users-panel/user/*" (fn [{[selected-user] :path-params :as request}]
                                               {:status 200
                                                :headers {"Content-Type" "text/html"}
                                                :body (str (user-panel-fragment conf selected-user))})
   "GET /frags/pages/all-users-panel/create-user" (partial respond-create-user-fragment conf)
   "POST /actions/pages/all-user-panel/submit-user" (partial respond-submit-user conf)
   "POST /actions/pages/all-user-panel/delete-user/*" (partial respond-delete-user conf)})
