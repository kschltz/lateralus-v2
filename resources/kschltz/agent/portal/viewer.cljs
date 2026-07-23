(ns kschltz.agent.portal.viewer
  "Sticky-composer session viewer for Lateralus × Portal.

   Loaded into the Portal UI cljs runtime via `portal.api/eval-str`.
   Renders the transcript atom and a sticky input + Send that RPC-calls
   `kschltz.agent.portal/reply!` on the JVM host."
  (:require [portal.colors :as c]
            [portal.ui.api :as p]
            [portal.ui.rpc :as rpc]
            [portal.ui.styled :as d]
            [portal.ui.theme :as theme]
            [reagent.core :as r]))

(def ^:private reply-sym 'kschltz.agent.portal/reply!)

(defn- role-color [theme role]
  (case role
    :user      (::c/tag theme)
    :assistant (::c/string theme)
    :system    (::c/package theme)
    :tool      (::c/number theme)
    :thinking  (::c/uri theme)
    (::c/text theme)))

(defn- turn-card [theme turn]
  (let [role (keyword (:role turn))
        color (role-color theme role)]
    [d/div
     {:style {:border-left  [3 :solid color]
              :padding-left 10
              :margin       "8px 0"
              :opacity      (if (= role :thinking) 0.75 1)}}
     [d/div
      {:style {:color      color
               :font-size  11
               :font-weight :bold
               :margin-bottom 4
               :text-transform :uppercase}}
      (name role)]
     (when (seq (:thinking turn))
       [:pre
        {:style {:margin      "0 0 6px 0"
                 :white-space "pre-wrap"
                 :opacity     0.7
                 :font-size   12}}
        (str "[thinking]\n" (:thinking turn))])
     (when (seq (:text turn))
       [:pre
        {:style {:margin      0
                 :white-space "pre-wrap"
                 :font-size   13}}
        (:text turn)])]))
(defn- composer []
  (let [draft (r/atom "")
        busy? (r/atom false)]
    (fn [theme status]
      (let [waiting? (= status :waiting)
            disabled? (or @busy? (not waiting?))]
        [d/div
         {:style {:display       :flex
                  :gap           8
                  :align-items   :stretch
                  :padding-top   10
                  :border-top    [1 :solid (::c/border theme)]
                  :margin-top    12}}
         [:input
          {:type        "text"
           :value       @draft
           :disabled    disabled?
           :placeholder (if waiting?
                          "Message lateralus… (/quit to exit)"
                          "Waiting for the agent…")
           :style       {:flex            1
                         :padding         "8px 10px"
                         :border-radius   (:border-radius theme)
                         :border          [1 :solid (::c/border theme)]
                         :background      (::c/background2 theme)
                         :color           (::c/text theme)
                         :outline         :none}
           :on-change   (fn [e]
                          (reset! draft (.. e -target -value)))
           :on-key-down (fn [e]
                          (when (and (= (.-key e) "Enter")
                                     (not disabled?)
                                     (seq @draft))
                            (.preventDefault e)
                            (let [text @draft]
                              (reset! busy? true)
                              (-> (rpc/call reply-sym {:text text})
                                  (.then (fn [_]
                                           (reset! draft "")
                                           (reset! busy? false)))
                                  (.catch (fn [_]
                                            (reset! busy? false)))))))}]
         [d/div
          {:disabled disabled?
           :style    {:padding       "8px 14px"
                      :cursor        (if disabled? :not-allowed :pointer)
                      :border-radius (:border-radius theme)
                      :border        [1 :solid (::c/border theme)]
                      :background    (if waiting?
                                       (::c/boolean theme)
                                       (::c/background2 theme))
                      :color         (::c/text theme)
                      :opacity       (if disabled? 0.5 1)
                      :user-select   :none}
           :on-click (fn [e]
                       (.stopPropagation e)
                       (when (and (not disabled?) (seq @draft))
                         (let [text @draft]
                           (reset! busy? true)
                           (-> (rpc/call reply-sym {:text text})
                               (.then (fn [_]
                                        (reset! draft "")
                                        (reset! busy? false)))
                               (.catch (fn [_]
                                         (reset! busy? false)))))))}
          "Send"]]))))

(defn session-view [value]
  (let [theme  (theme/use-theme)
        turns  (or (:turns value) [])
        status (or (:status value) :idle)]
    [d/div
     {:style {:display        :flex
              :flex-direction :column
              :min-height     "60vh"
              :padding        (:padding theme)
              :background     (::c/background theme)
              :color          (::c/text theme)
              :border-radius  (:border-radius theme)
              :border         [1 :solid (::c/border theme)]}}
     [d/div
      {:style {:font-size     12
               :opacity       0.7
               :margin-bottom 8}}
      (str "session " (or (:session-id value) "?")
           " · " (name status))]
     [d/div
      {:style {:flex       1
               :overflow-y :auto
               :min-height 200}}
      (for [t turns]
        ^{:key (or (:id t) (str (:ts t) (:role t)))}
        [turn-card theme t])]
     [composer theme status]]))

(defn session? [value]
  (and (map? value)
       (contains? value :turns)
       (contains? value :status)
       (contains? value :session-id)))

(p/register-viewer!
 {:name :kschltz.agent.portal.viewer/session
  :predicate session?
  :component session-view})
