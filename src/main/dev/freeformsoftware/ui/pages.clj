(ns dev.freeformsoftware.ui.pages
  (:require
   [dev.freeformsoftware.ui.html-fragments :as ui.frag]
   [dev.freeformsoftware.ui.pages.user-management :as user-management]
   [dev.freeformsoftware.ui.pages.room-management :as room-management]
   [hiccup2.core :as h]
   [ring.util.response :as resp]
   [dev.freeformsoftware.server.route-utils :as server.route-utils]))

(set! *warn-on-reflection* true)

(def pages
  [{:id        :user-management
    :route     "/pages/users"
    :title     "User Management"
    :body-fn   #'user-management/users-list-page
    :action-fn #'user-management/action-button-fragment}
   {:id        :room-management
    :route     "/pages/rooms"
    :title     "Room Management"
    :body-fn   #'room-management/rooms-list-page
    :action-fn #'room-management/action-button-fragment}])

(def page-by-id
  (-> (group-by :id pages)
      (update-vals first)))

(defn root-admin-page
  [conf req page]
  (let [{:keys [route title body-fn action-fn]} (get page-by-id page)]
    (ui.frag/html-body
     conf
     (ui.frag/page-with-sidebar
      [:h1.text-2xl.p-4.text-center.font-bold (:app-title conf)]
      [:div.flex.flex-col.font-bold.h-full.gap-2
       (ui.frag/sidebar
        [:div.flex.flex-col
         (for [{:keys [route title id]} pages]
           [:a
            {:class (if (= page id)
                      ui.frag/selected-link-classes
                      ui.frag/clickable-link-classes)
             :href  route}
            title])]
        (when action-fn (action-fn conf req)))]
      (when body-fn (body-fn conf req))))))

(defn page-routes
  [conf]
  (server.route-utils/merge-routes
   {"GET /"               (fn [request]
                            (resp/redirect "/pages/users"))
    "GET /pages/users/**" (fn [request]
                            (-> (resp/response (str (h/html (root-admin-page conf request :user-management))))
                                (resp/content-type "text/html")))
    "GET /pages/rooms/**" (fn [request]
                            (-> (resp/response (str (h/html (root-admin-page conf request :room-management))))
                                (resp/content-type "text/html")))}
   (user-management/routes conf)
   (room-management/routes conf)))
