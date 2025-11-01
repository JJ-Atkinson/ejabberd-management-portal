(ns dev.freeformsoftware.ui.html-fragments
  (:require
   [hiccup2.core :as h]
   [hiccup.page :as page]))

(set! *warn-on-reflection* true)

(defn html-body
  "Wrapper for full HTML page with CSS, JS, and body content"
  [{:keys [env] :as conf} & body]
  [:html
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:link {:rel "stylesheet" :href "/css/output.css"}]
    [:script {:src "/js/bundle.js"}]
    (when (= env :dev) [:script {:src "/js/dev-ws.js" :defer true}])]
   [:body body]])

(defn background
  "Includes CSRF for HTMX"
  [& body]
  [:div.bg-orange-50.flex.flex-row.items-stretch.h-screen.relative
   body
   [:div#modal-container.absolute]])

(def mobile-sidebar-open-script
  "on click
     add .hidden to me
     remove .hidden from #sidebar then remove .hidden from #sidebar-backdrop
     wait 10ms
     remove .-translate-x-full from #sidebar
     add .fixed to <body/> then add .inset-0 to <body/>")

(def mobile-sidebar-close-script
  "on click
     remove .hidden from #hamburger
     add .-translate-x-full to #sidebar
     wait 300ms
     add .hidden to #sidebar then add .hidden to #sidebar-backdrop
     remove .fixed from <body/> then remove .inset-0 from <body/>")

(defn page-with-sidebar
  [header sidebar body]
  (background
   [:div.flex.flex-col.h-full.flex-1
    ;; Header: hamburger (mobile), title (center), spacer (mobile)
    [:div.grid.items-center.bg-orange-50.border-b-2.border-slate-900
     {:class "grid-cols-[auto_1fr_auto] md:grid-cols-1"}
     [:button#hamburger.md:hidden.m-4.bg-slate-700.text-white.text-xl.w-12.h-12.flex.items-center.justify-center
      {:_ mobile-sidebar-open-script} "☰"]
     [:div header]
     [:div.md:hidden.w-20]]

    ;; Main: backdrop (mobile) + sidebar + body
    [:div.flex.flex-row.items-stretch.flex-1.overflow-hidden.relative
     [:div#sidebar-backdrop.hidden.md:hidden.fixed.inset-0.bg-black.bg-opacity-50.z-40
      {:_ mobile-sidebar-close-script}]
     [:div#sidebar.flex.flex-col.md:border-r-2.border-r-slate-900.md:w-64.w-full.bg-orange-50.z-50.md:relative.fixed.inset-0.transition-transform.duration-300.overscroll-contain
      {:class "hidden md:flex -translate-x-full md:translate-x-0"}
      [:button.md:hidden.absolute.top-4.right-4.bg-slate-700.text-white.text-xl.w-12.h-12.flex.items-center.justify-center
       {:_ mobile-sidebar-close-script} "✕"]
      [:div.md:hidden.h-20]
      [:div.flex-1.overflow-y-auto.overscroll-contain sidebar]]
     [:div.flex-1.overflow-y-auto body]]]))

(defn sidebar
  [center
   action]
  [:div.flex.flex-col.items-stretch.h-full.gap-4.overflow-hidden.pb-24.md:pb-3
   [:div.flex-grow.overflow-y-auto.overscroll-contain center]
   [:div.flex-shrink-0
    action]])

(defn modal-container
  [contents]
  [:div.flex.flex-row.items-center.justify-center.h-screen.w-screen.absolute.modal-container.min-h-fit.min-w-fit
   {:class ["bg-gray-50/75 z-[60]"]
    :_ "on closeModal remove me"}
   [:div.max-h-screen.overflow-y-auto
    {:class ["border-2 bg-orange-50 border-slate-900 min-w-fit min-h-fit" "w-screen md:w-3/4" "max-w-full md:max-w-lg"
             "p-5"]}
    contents]])

(defn right-aligned
  [contents]
  [:div.self-end.flex contents])

(def input-classes
  ["border-2"
   "border-gray-300"

   "w-full"
   "p-1"
   "focus:border-teal-600"
   "focus:ring-teal-600"])

(def button-classes
  #_'[bg-teal-600
      hover:bg-teal-800
      text-white
      py-1
      text-center]
  ["font-bold"
   "border-2"
   "py-1"
   "px-2"
   "hover:border-teal-800"
   "hover:bg-teal-200"])

(def cancel-button-classes
  ["bg-slate-700"
   "hover:bg-slate-200"
   "text-white"
   "hover:text-black"
   "border-2"
   "border-slate-700"
   "text-white"
   "py-1"
   "px-2"
   "text-center"])

(def delete-button-classes
  ["bg-red-500"
   "hover:bg-red-300"
   "text-white"
   "py-1"
   "px-2"
   "text-center"])

(def checkbox-classes
  ["checkbox-input"])

(def clickable-link-classes
  ["bg-slate-700"
   "hover:bg-slate-200"
   "text-white"
   "hover:text-black"
   "pl-4"
   "block"
   "cursor-pointer"
   "py-2"])

(defn disable-action-buttons-on-click-script
  "Generates hyperscript that disables all .disabling-action-button elements 
   and shows the .processing-notification within the specified form.
   Use this on action buttons (delete/save/submit/apply) to prevent duplicate submissions."
  [form-id]
  (str "on htmx:beforeRequest 
     remove .hidden from <#" form-id " .processing-notification/> 
     add @disabled='true' to .disabling-action-button
     add .opacity-50 to .disabling-action-button
     add .pointer-events-none to .disabling-action-button"))

(def selected-link-classes
  ["bg-slate-500"
   "text-white"
   "pl-6"
   "block"
   "cursor-pointer"
   "py-2"])

(defn form-modal
  [title fields button-bar]
  [:div.flex.flex-col
   [:h3.self-start.text-2xl.text-extrabold
    title]
   [:div.h-4]
   [:div.self-stretch.flex-grow
    fields]
   [:div.h-4]
   button-bar])

(defn form
  ([title fields button-bar]
   (form title fields button-bar nil))
  ([title fields button-bar error]
   [:div.flex.flex-col.gap-4
    [:h3.self-start.text-2xl.text-extrabold
     title]
    [:div.self-stretch.flex-grow.flex.flex-col.gap-4
     fields]
    (when error
      [:div.p-3.bg-red-100.border.border-red-400.text-red-700
       error])
    button-bar]))

(defn tooltip
  ([trigger contents]
   (tooltip trigger contents ["inline-block"]))
  ([trigger contents extra-classes]
   [:div
    {:class extra-classes
     :_
     "on mouseenter
          set $showPending to true
          wait 0.5s
          if $showPending
            set $triggerEl to the first .tooltip-trigger in me
            call positionTooltip($triggerEl)
            set $tooltipEl to $triggerEl._tooltip
            if $tooltipEl exists
              remove .hidden from $tooltipEl
              remove .opacity-0 from $tooltipEl
              add .opacity-100 to $tooltipEl
            end
          end
       on mouseleave
         set $showPending to false
         set $triggerEl to the first .tooltip-trigger in me
         set $tooltipEl to $triggerEl._tooltip
         if $tooltipEl exists
           remove .opacity-100 from $tooltipEl
           wait 125ms
           add .hidden to $tooltipEl
         end
       on touchstart
         set $triggerEl to the first .tooltip-trigger in me
         call positionTooltip($triggerEl)
         set $tooltipEl to $triggerEl._tooltip
         if $tooltipEl exists
           remove .hidden from $tooltipEl
           remove .opacity-0 from $tooltipEl
           add .opacity-100 to $tooltipEl
         end
         wait 2s
         if $tooltipEl exists
           remove .opacity-100 from $tooltipEl
           wait 125ms
           add .hidden to $tooltipEl
         end"}
    [:div.tooltip-trigger trigger]
    [:div.tooltip.fixed.hidden.px-4.py-2.text-sm.text-white.bg-gray-800.shadow-lg.transition-opacity.duration-125
     {:class "z-[70] opacity-0"}
     contents]]))

(defn form-input
  [{:keys [type label id placeholder name] :as input-props} & input-body]
  [:div
   [:label.text-lg.font-semibold {:for id} label]
   [(case type
      "textarea" :textarea
      "select" :select
      :input)
    (cond-> input-props
      (not placeholder) (assoc :placeholder label)
      (not name) (assoc :name id))
    input-body]])

(defn no-jwt-landing-page
  [conf]
  (html-body
   conf
   (background
    [:div.flex.items-center.justify-center.h-screen.w-screen
     [:div.border-2.py-10.px-6
      [:h1.text-2xl.font-bold "You must have a link to visit this site."]]])))
