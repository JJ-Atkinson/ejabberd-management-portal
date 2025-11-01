(ns dev.freeformsoftware.ui.pages.signup
  (:require
   [dev.freeformsoftware.ejabberd.ejabberd-api :as api]
   [dev.freeformsoftware.ui.html-fragments :as ui.frag]
   [hiccup2.core :as h]))

(set! *warn-on-reflection* true)

(defn signup-form
  "Password creation form for new users."
  [conf name error]
  (ui.frag/html-body
   conf
   (ui.frag/background
    [:div.flex.items-center.justify-center.h-screen.w-screen
     [:div.border-2.p-8.m-4.max-w-lg.w-full
      [:form {:method "POST" :action "/signup"}
       (ui.frag/form
         [:div
          [:h1.text-2xl.font-bold.mb-4 (str "Welcome, " name)]
          [:p.text-lg.text-gray-700.mb-6 "Please create a password"]]

         [:div.flex.flex-col.gap-4
          ;; Password field
          (ui.frag/form-input {:type        "password"
                               :id          "password"
                               :label       "Password"
                               :placeholder "Enter your password"
                               :class       ui.frag/input-classes
                               :required    true})

          ;; Confirm password field
          (ui.frag/form-input {:type        "password"
                               :id          "confirm-password"
                               :label       "Confirm Password"
                               :placeholder "Re-enter your password"
                               :class       ui.frag/input-classes
                               :required    true})]

         ;; Submit button - right aligned
         (ui.frag/right-aligned
          [:button
           {:type  "submit"
            :class ui.frag/button-classes}
           "Apply"])

         error)]]])))

(defn signup-confirmation
  "Confirmation page after successful password creation."
  [conf name user-id]
  (let [xmpp-domain  (:xmpp-domain conf)
        xmpp-address (str user-id "@" xmpp-domain)]
    (ui.frag/html-body
     conf
     (ui.frag/background
      [:div.flex.items-center.justify-center.h-screen.w-screen
       [:div.border-2.p-10.max-w-md.w-full
        [:h1.text-2xl.font-bold.mb-4 "Account Created!"]
        [:p.text-gray-700.mb-6 (str "Welcome " name ", your account has been created successfully.")]
        [:p.text-gray-700.mb-4 "You can now sign in with your password using the address:"]
        [:p.text-lg.font-mono.font-bold.text-center.border-2.p-3.rounded.mb-4 xmpp-address]
        [:p.text-gray-600.text-sm "Use this address with any XMPP client to connect."]]]))))

(defn handle-signup-post
  "Handles POST request for signup - validates passwords and creates account."
  [{:keys [ejabberd-api] :as conf} request]
  (let [claims           (:jwt-claims request)
        user-id          (:user-id claims)
        name             (:name claims)
        params           (:params request)
        password         (:password params)
        confirm-password (:confirm-password params)]
    (cond
      (not= password confirm-password)
      {:status  400
       :headers {"Content-Type" "text/html"}
       :body    (str (h/html
                      (signup-form conf name "Passwords do not match. Please try again.")))}

      (< (count password) 12)
      {:status  400
       :headers {"Content-Type" "text/html"}
       :body    (str (h/html
                      (signup-form conf name "Password must be at least 12 characters long.")))}

      :else
      ;; Passwords match and meet requirements - change the password via API
      (try
        (api/change-password ejabberd-api user-id password)
        {:status  200
         :headers {"Content-Type" "text/html"}
         :body    (str (h/html (signup-confirmation conf name user-id)))}
        (catch Exception e
          {:status  500
           :headers {"Content-Type" "text/html"}
           :body    (str (h/html
                          (signup-form conf name (str "Failed to set password: " (ex-message e)))))})))))

(defn routes
  [conf]
  {"GET /signup"  (fn [request]
                    (let [claims (:jwt-claims request)
                          name   (:name claims)]
                      {:status  200
                       :headers {"Content-Type" "text/html"}
                       :body    (str (h/html (signup-form conf name nil)))}))

   "POST /signup" (partial handle-signup-post conf)})


