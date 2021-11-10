(ns nextjournal.ui.components.icon)

(defn html [name {:keys [class size style]}]
  [:svg {:class (str "icon " class)
         :style (merge (when size {:width (str size "px") :height (str size "px")})
                       style)}
   [:use {:xlink:href (str "/images/icons.svg#" name)}]])

(defn chevron-right [{:keys [size] :or {size 16}}]
  [:svg {:width:width size :height size :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
   [:path {:fill-rule "evenodd" :d "M7.293 14.707a1 1 0 010-1.414L10.586 10 7.293 6.707a1 1 0 011.414-1.414l4 4a1 1 0 010 1.414l-4 4a1 1 0 01-1.414 0z" :clip-rule "evenodd"}]])

(defn chevron-double-right [{:keys [size] :or {size 16}}]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :width size :height size :class "transition-all"}
   [:path {:fill-rule "evenodd" :d "M10.293 15.707a1 1 0 010-1.414L14.586 10l-4.293-4.293a1 1 0 111.414-1.414l5 5a1 1 0 010 1.414l-5 5a1 1 0 01-1.414 0z" :clip-rule "evenodd"}]
   [:path {:fill-rule "evenodd" :d "M4.293 15.707a1 1 0 010-1.414L8.586 10 4.293 5.707a1 1 0 011.414-1.414l5 5a1 1 0 010 1.414l-5 5a1 1 0 01-1.414 0z" :clip-rule "evenodd"}]])

(defn chevron-double-left [{:keys [size] :or {size 16}}]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :width size :height size :class "transition-all"}
   [:path {:fill-rule "evenodd" :d "M15.707 15.707a1 1 0 01-1.414 0l-5-5a1 1 0 010-1.414l5-5a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 010 1.414zm-6 0a1 1 0 01-1.414 0l-5-5a1 1 0 010-1.414l5-5a1 1 0 011.414 1.414L5.414 10l4.293 4.293a1 1 0 010 1.414z" :clip-rule "evenodd"}]])

(def divider
  [:svg.flex-shrink-0.h-5.w-5.text-gray-300 {:xmlns "http://www.w3.org/2000/svg" :fill "currentColor" :viewBox "0 0 20 20" :aria-hidden "true"}
   [:path {:d "M5.555 17.776l8-16 .894.448-8 16-.894-.448z"}]])

(defn menu [{:keys [size] :or {size 16}}]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor" :width size :height size}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M4 6h16M4 12h16M4 18h16"}]])

(defn view [name {:keys [class size style] :as options}]
  #?(:cljs
     [:svg {:class (str "icon " class)
            :style (merge (when size {:width size :height size})
                          style)}
      [:use {:xlink-href (str "#" name)}]]
     :clj
     (html name options)))

(defn offline [{:keys [class size style] :or {size 16}}]
  [:svg {:view-box "0 0 48 48"
         :class (str "icon " class)
         :style (merge (when size {:width size :height size})
                       style)}
   [:g {:transform "matrix(0.533333, 0, 0, 0.533333, -2.666664, 0)"}
    [:path {:d "M 43,16 C 29.560705,16 19.950054,27.2898 19.9375,39.3125 11.412587,40.82269 5,48.4397 5,57 5,67.8653 13.41566,76 23,76 l 12,0 c 1.584938,0.02241 3.042726,-1.414904 3.042726,-3 0,-1.585096 -1.457788,-3.022415 -3.042726,-3 l -12,0 c -6.21498,0 -12,-5.1737 -12,-13 0,-6.1395 5.441794,-12 12,-12 1.717366,2.72e-4 3.219002,-1.738465 2.96875,-3.4375 C 24.634479,32.06124 32.079557,22 43,22 c 7.377682,0 12.78385,4.28643 15.3125,9.34375 0.712769,1.39808 2.631986,2.022941 4.03125,1.3125 3.060852,-1.53043 6.749308,-1.31916 9.625,0.40625 C 74.844442,34.78791 77,37.91667 77,43 c 0.0097,1.39646 1.123053,2.704668 2.5,2.9375 C 84.96595,46.85461 89,52.443 89,58 89,64.6895 83.856933,70 75,70 l -10,0 c -1.584938,-0.02241 -3.042726,1.414904 -3.042726,3 0,1.585096 1.457788,3.022415 3.042726,3 l 10,0 C 86.480751,76 95,67.9699 95,58 95,50.3686 90.171652,42.98901 82.71875,40.625 82.03627,34.88548 79.096102,30.34516 75.03125,27.90625 71.167333,25.5879 66.510008,25.12971 62.1875,26.4375 58.322886,20.57389 51.621681,16 43,16 z m -1.34375,45.96875 c -1.123172,0.120413 -2.143335,0.931216 -2.514182,1.998215 -0.370848,1.066999 -0.07345,2.335734 0.732932,3.126785 L 45.75,73 39.875,78.875 c -1.223497,1.084361 -1.283954,3.222953 -0.123672,4.374704 C 40.317973,83.812183 41.209819,84 42,84 c 0.770415,0 1.574842,-0.276193 2.125,-0.90625 l 5.875,-5.875 5.875,5.875 C 56.414439,83.711531 57.229941,84 58,84 58.790248,84 59.67046,83.823664 60.248672,83.249704 61.408954,82.097954 61.348497,79.959361 60.125,78.875 L 54.25,73 60.125,67.09375 c 0.892842,-0.870601 1.14962,-2.326705 0.608429,-3.450192 C 60.192237,62.520071 58.893557,61.813242 57.65625,61.96875 56.982925,62.057531 56.343185,62.383012 55.875,62.875 L 50,68.75 44.125,62.875 c -0.628627,-0.654397 -1.566034,-0.998508 -2.46875,-0.90625 z"}]]])
