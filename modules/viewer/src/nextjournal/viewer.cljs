(ns nextjournal.viewer
  (:refer-clojure :exclude [meta with-meta vary-meta])
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [clojure.core :as core]
            [cognitect.transit :as transit]
            [goog.object]
            [nextjournal.devcards :as dc]
            [nextjournal.viewer.code :as code]
            [nextjournal.viewer.katex :as katex]
            [nextjournal.viewer.markdown :as markdown]
            [nextjournal.viewer.mathjax :as mathjax]
            [nextjournal.viewer.plotly :as plotly]
            [nextjournal.viewer.vega-lite :as vega-lite]
            [react :as react]
            [reagent.core :as reagent]
            [reagent.dom]
            [clojure.string :as str]
            [sci.impl.vars]))

(defn edn-type [tag value]
  (case tag
    var :edn-var
    object (if (= (first value) 'clojure.lang.Atom)
             :edn-atom
             :edn-object)
    nextjournal/empty :edn-empty
    :edn-unknown-tag))

(defprotocol ITypeKey
  (-type-key [x] "Returns type name as keyword"))

(defn value-type [value]
  (cond (and (exists? js/Element)
             (instance? js/Element value)) :element
        (satisfies? ITypeKey value) (-type-key value)
        (var? value) :var
        (implements? IDeref value) :derefable
        (map? value) :map
        (array? value) :array
        (set? value) :set
        (vector? value) :vector
        (list? value) :list
        (seq? value) :list
        (fn? value) :fn
        (uuid? value) :uuid
        (string? value) :string
        (or (number? value) (= "bigint" (goog/typeOf value))) :number
        (keyword? value) :keyword
        (symbol? value) :symbol
        (nil? value) :nil
        (boolean? value) :boolean
        (inst? value) :inst
        (and (transit/tagged-value? value)
             (not (coll? value))) :transit-tagged-value
        (goog/isObject value) :object
        :else :untyped))

(defn obj->clj
  [obj]
  (-> (fn [result key]
        (let [v (aget obj key)]
          (if (= "function" (goog/typeOf v))
            result
            (assoc result key v))))
      (reduce {} (goog.object/getKeys obj))))

(defn coll-decoration [type]
  (case type
    (:vector :array) ["[" "]"]
    :set ["#{" "}"]
    :list ["(" ")"]
    (:map :object) ["{" "}"]))

(def increase-items 20)

(defn color-classes [selected?]
  {:value-color (if selected? "white-90" "dark-green")
   :symbol-color (if selected? "white-90" "dark-blue")
   :prefix-color (if selected? "white-50" "black-30")
   :label-color (if selected? "white-90" "black-60")
   :badge-background-color (if selected? "bg-white-20" "bg-black-10")})

(defn count-badge [selected? coll]
  (let [{:keys [badge-background-color label-color]} (color-classes selected?)]
    [:span.text-center.flex.items-center
     {:class (str badge-background-color " " label-color)
      :style {:padding-left "0.5em" :padding-right "0.5em" :height "1.1em" :border-radius 7}}
     (count coll)]))

(defn more-button [visible-nb-items-ratom {:keys [expanded? count] :as _opts}]
  [(if expanded? :div.result-data-field :span)
   {:on-click  #(swap! visible-nb-items-ratom + increase-items)}
   [:span.monospace
    (if expanded?
      {:class "p-1 mt-3 -ml-1 hover:bg-gray-200 rounded cursor-pointer"
       :style {:font-size 12}}
      {:class "pl-2 text-gray-500 inspected-value"})
    (- count @visible-nb-items-ratom)
    [:span " more…"]]])

(defn browsify-button [path {:nextjournal/keys [dispatch]} view]
  [:span.browsify-button
   {:class "hover:bg-gray-200 cursor-pointer rounded"
    :on-click #(dispatch {:kind :nav :path path})}
   view])

(defn navigable-key?
  "Check if we allow the key to be navibable based on the type.
  Currently we only support primitive types, except symbols."
  [item]
  (or (number? item) (string? item) (keyword? item)))

(defn navigable-item?
  "Determines whether an item should be made clickable for datafy/nav. Is the view
  editable? The runtime active? Does the runtime support datafy/nav? Did the node
  execute since start of the runtime? ..."
  [options path item]
  (and (empty? path)
       (:nextjournal/navigable? options)
       (navigable-key? item)))

(declare inspect)

(defn inspect-coll [_type _options _coll]
  (let [visible-nb-items (reagent/atom 20)]
    (fn [type {:as options :keys [expanded path]} coll]
      (let [truncated? (:nextjournal/truncated? (core/meta coll))
            expanded? (get @expanded path)
            parent (vec (drop-last path))
            short? (and (seq path) (not (get @expanded parent)))
            items (cond-> coll
                    (object? coll) js->clj)
            count (count items)
            visible-items (take @visible-nb-items items)
            map-like? (case type (:map :object) true false)
            [open close] (coll-decoration type)]
        [:span {:class (if expanded? "result-data-expanded" "result-data-collapsed")}
         [:span
          (when-not short?
            {:class "pointer"
             :on-click (fn []
                         (swap! expanded update path not)
                         (when-let [on-expand (:on-expand options)]
                           (js/requestAnimationFrame on-expand)))})
          (when-not (or (empty? items) short?)
            [:div.disclose {:class (when-not expanded? "collapsed")}])
          [:span.inspected-value
           (case type
             :map "Map"
             :object "Object"
             :array "Array"
             :set "Set"
             :list "List"
             :vector "Vector")]
          (when (or (not map-like?) truncated?)
            [:span.inspected-value
             "(" count (when truncated? "+") ")"])]
         (when-not short? [:span.inspected-value open])
         (when-not (or (empty? items) short?)
           (doall (map-indexed (fn [i item]
                                 ^{:key i}
                                 [(if expanded? :div.result-data-field :span)
                                  (when (or expanded? map-like?)
                                    (let [item (if (or map-like? (set? coll)) item i)]
                                      (cond->> [:span.inspected-value
                                                {:class (if map-like? "syntax-key" "syntax-index")}
                                                (if map-like? [inspect (update options :path conj i) item] i) ": "]
                                        (navigable-item? options path item)
                                        (conj [browsify-button (conj (:nav/path options) item) options]))))
                                  (let [item (cond (map? coll) (get coll item)
                                                   (object? coll) (goog.object/get coll item)
                                                   :else item)]
                                    [inspect (update options :path conj i) item])
                                  (when (or (> count @visible-nb-items) (< i (dec count)))
                                    [:span.inspected-value ", "])])
                               (if map-like?
                                 (keys visible-items)
                                 visible-items))))
         (when (and (not short?) (> count @visible-nb-items))
           [more-button visible-nb-items {:expanded? expanded? :count count}])
         (when-not short? [:span.inspected-value close])]))))

(defn value-of
  "Safe access to a value at key a js object.

   Returns `'forbidden` if reading the property would result in a `SecurityError`.
   https://developer.mozilla.org/en-US/docs/Web/Security/Same-origin_policy"
  [obj k]
  (try
    (let [v (j/get obj k)]
      (.-constructor v) ;; test for SecurityError
      v)
    (catch js/Error ^js _
      'forbidden)))

(defn inspect-object [_inspect _options _obj]
  (let [visible-nb-items (reagent/atom 20)]
    (fn [inspect {:as options :keys [expanded path]} obj]
      (let [expanded? (get @expanded path)
            parent (vec (drop-last path))
            short? (and (seq path) (not (get @expanded parent)))
            empty? (goog.object/isEmpty obj)
            counter (atom -1)
            t (type obj)
            items (js-keys obj)
            count (count items)]
        [:span {:class (if expanded? "result-data-expanded" "result-data-collapsed")}
         [:span.inspected-value
          (when-not short?
            {:class "pointer"
             :on-click (fn []
                         (swap! expanded update path not)
                         (when-let [on-expand (:on-expand options)]
                           (js/requestAnimationFrame on-expand)))})
          (when-not (or empty? short?)
            [:div.disclose {:class (when-not expanded? "collapsed")}])
          (if t (.-name t) "Object")]
         (when-not short? [:span.inspected-value " {"])
         (when-not (or empty? short?)
           (for [k (take @visible-nb-items items)
                 :when (or (not t) (.hasOwnProperty obj k))
                 :let [i (swap! counter inc)]]
             ^{:key i}
             [(if expanded? :div.result-data-field :span)
              (when (and (not expanded?) (< 0 i)) [:span.inspected-value ", "])
              [:span.inspected-value
               {:class "syntax-tag"}
               k ": "]
              [inspect (update options :path conj k)  (value-of obj k)]]))
         (when (and (not (or empty? short?)) (> count @visible-nb-items))
           [more-button visible-nb-items {:expanded? expanded? :count count}])
         (when-not short?
           [:span.inspected-value
            "}"])]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom metadata handling - supporting any cljs value - not compatible with core meta

(defn meta? [x] (contains? x :nextjournal/value))

(defn meta [data]
  (if (meta? data)
    data
    (assoc (core/meta data)
      :nextjournal/value (cond-> data
                           ;; IMeta is a protocol in cljs
                           (satisfies? IWithMeta data) (core/with-meta {})))))

(defn with-meta [data m]
  (cond (meta? data) (assoc m :nextjournal/value (:nextjournal/value data))
        (satisfies? IWithMeta data) (core/with-meta data m)
        :else
        (assoc m :nextjournal/value data)))

(defn vary-meta [data f & args]
  (with-meta data (apply f (meta data) args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Viewers (built on metadata)

(defn with-viewer
  "The given viewer will be used to display data"
  [data viewer]
  (vary-meta data assoc :nextjournal/viewer viewer))

(defn with-viewers
  "Binds viewers to types, eg {:boolean view-fn}"
  [data viewers]
  (vary-meta data assoc :nextjournal/viewers viewers))

(defn view-as
  "Like `with-viewer` but takes viewer as 1st argument"
  [viewer data]
  (with-viewer data viewer))

(defn html [v]
  (with-viewer v (if (string? v) :html :hiccup)))

(def !viewers
  ;; viewers registered here can be invoked via `:nextjournal/viewer` metadata
  ;; (as returned by view-as, with-viewer, and with-viewers)
  (reagent/atom {:hiccup #(reagent/as-element %)
                 :element (fn [v]
                            ;; Should we do something else here?
                            (if (j/get v :parentNode)
                              "DOM Element"
                              (view-as :reagent [:div {:ref #(when %
                                                               (when-let [childElement (.-firstChild %)]
                                                                 (.removeChild % childElement))
                                                               (.appendChild % v))}])))
                 :flex-col (fn [xs] (view-as :hiccup (into [:div.flex.flex-col] (map (fn [x] [inspect x])) xs)))
                 :reagent #(reagent/as-element (cond-> % (fn? %) vector))
                 :html (fn [html-str] (view-as :hiccup [:div {:dangerouslySetInnerHTML {:__html html-str}}]))
                 :latex (fn [s] (view-as :html (katex/to-html-string s)))
                 :mathjax mathjax/viewer
                 :plotly plotly/viewer
                 :vega-lite vega-lite/viewer
                 :markdown markdown/viewer
                 :code code/viewer}))

(defn register-viewer!
  "Registers a viewer function under a given name."
  [name viewer]
  (swap! !viewers assoc name viewer))

(defn register-viewers!
  "Registers a viewers map."
  [viewers]
  (swap! !viewers merge viewers))

(declare inspect)

(def ^:dynamic *eval-form* nil)

(defn render-with-viewer [options viewer value]
  ((cond (keyword? viewer) (or
                            (get-in options [:nextjournal/viewers viewer])
                            (@!viewers viewer)
                            (prn :viewer-not-found viewer)
                            inspect)
         (fn? viewer) viewer
         (list? viewer) (if (fn? *eval-form*)
                          (*eval-form* viewer)
                          (throw (js/Error. "Viewer is a list but `*eval-form*` is not bound to a function.")))
         :else
         (throw (js/Error. (str "Viewer is not a keyword or function or list: " viewer))))
   value
   options))

(defn ^:export inspect
  ([data]
   (inspect {} data))
  ([options data]
   (let [{:as options :keys [path expanded]} (cond-> options (not (:path options)) (merge {:path [] :expanded (reagent/state-atom (reagent/current-component))}))
         {:as value-meta
          :nextjournal/keys [value tag]} (meta data)
         options (update options :nextjournal/viewers merge (:nextjournal/viewers value-meta))
         type-key (if tag
                    (edn-type tag value)
                    (value-type value))
         viewer-key (:nextjournal/viewer value-meta type-key)
         viewer (or (when (or (fn? viewer-key) (list? viewer-key)) viewer-key)
                    (get-in options [:nextjournal/viewers viewer-key])
                    (@!viewers viewer-key))]
     (cond
       (react/isValidElement data) data
       viewer (inspect options (render-with-viewer options viewer value))
       :else
       (case type-key
         :edn-var [:span.inspected-value
                   [:span.syntax-tag "#'" (str (first value)) " "]
                   [inspect options (second value)]]
         :edn-atom [:span.inspected-value
                    [:span.syntax-tag 'clojure.lang.Atom " "]
                    [inspect options (:val (get value 2))]]
         :edn-object [:span.inspected-value
                      [:span.syntax-tag "#" (str tag) " "]
                      [:span "["
                       [:span.syntax-tag (first value)] " "
                       [:span "0x" (.toString (second value) 16)] " "
                       [inspect options (nth value 2)] "]"]]
         :edn-empty [:span.inspected-value
                     [:span.syntax-tag "#"]]
         :edn-unknown-tag [:span.inspected-value
                           [:span.syntax-tag "#" (str tag) " "]
                           [inspect options (let [m (select-keys value-meta [:nextjournal/truncated?])]
                                              (cond-> value (seq m) (vary-meta merge m)))]]
         :var
         [:span.inspected-value
          "#'" [inspect options (.-sym ^clj data)]
          " "
          [inspect options @data]]

         :derefable
         [:span.inspected-value
          [:span.syntax-tag (-> data type pr-str) " "]
          [inspect options @data]]

         (:map
          #_:object
          :array
          :set
          :list
          :vector)
         [inspect-coll type-key options data]

         :transit-tagged-value
         [:span.inspected-value
          [:span.syntax-tag "#" (.-tag ^clj data) " "]
          [inspect options (.-rep ^clj data)]]

         :fn
         [:span.inspected-value
          [:span.syntax-tag "ƒ"] "()"]

         :uuid
         [:span.inspected-value
          [:span.syntax-tag "#uuid "]
          [inspect options (str data)]]

         :string
         [:span.syntax-string.inspected-value "\"" data "\""]

         :number
         [:span.syntax-number.inspected-value
          (if (js/Number.isNaN data)
            "NaN"
            (str data))]

         :keyword
         [:span.syntax-keyword.inspected-value (str data)]

         :symbol
         [:span.syntax-symbol.inspected-value (str data)]

         :nil
         [:span.syntax-nil.inspected-value "nil"]

         :boolean
         [:span.syntax-bool.inspected-value (str data)]

         :inst
         [:span.inspected-value
          [:span.syntax-tag "#inst "]
          [inspect options (.toISOString data)]]

         :object
         [inspect-object inspect options data]

         :untyped
         [:span.syntax-untyped.inspected-value (str (type data) "[" data "]")])))))


(comment
  :nav/path node-id ;; a list of keys we have navigated down to
  :nav/value node-id nav-path;; the object for a node-id and a nav-path (see above) ,
  )

(dc/defcard markdown-doc
  "Shows how to display a markdown document and viewers using the `:flex-col` viewer."
  [state]
  [inspect ^{:nextjournal/viewer :flex-col} [(view-as :markdown "# Hello Markdown\nLet's give *this* a try!")
                                             [1 2 3 4]
                                             {:hello [0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9]}
                                             (view-as :markdown "And some more [markdown](https://daringfireball.net/projects/markdown/).")]])

(dc/defcard viewer-overlays
  "Shows how to override how values are being displayed."
  [state]
  [:div.result-data
   [inspect (with-viewers @state
              {:number #(str/join (take % (repeat "*")))
               :boolean #(view-as :hiccup
                                  [:div.inline-block {:style {:width 12 :height 12}
                                                      :class (if % "bg-red" "bg-green")}])})]]
  {::dc/state {:a 1
               :b 2
               :c 3
               :d true
               :e false}})

(defn inspect-xf
  "Takes a data value with possibly metadata on it and returns a transducer
  that will calls `inspect` on every collection element. Use this in custom
  viewers for e.g. vectors to ensure custom viewers are passed down to the
  children."
  [x]
  (map (cond-> inspect
         (-> x meta :nextjournal/viewers seq)
         (partial (meta x)))))

(dc/when-enabled
 (def rule-30-state
   (let [rule30 {[1 1 1] 0
                 [1 1 0] 0
                 [1 0 1] 0
                 [1 0 0] 1
                 [0 1 1] 1
                 [0 1 0] 1
                 [0 0 1] 1
                 [0 0 0] 0}
         n 33
         g1 (assoc (vec (repeat n 0)) (/ (dec n) 2) 1)
         evolve #(mapv rule30 (partition 3 1 (repeat 0) (cons 0 %)))]
     (->> g1 (iterate evolve) (take 17)))))

(dc/defcard rule-30-types
  "Rule 30 using viewers based on types. Also shows how to use a named viewer for a number."
  [state]
  [:div.result-data
   [inspect (with-viewers
              @state
              {:number :cell ;; use the cell viewer for numbers
               :cell #(view-as :hiccup
                               [:div.inline-block {:class (if (zero? %)
                                                            "bg-white border-solid border-2 border-black"
                                                            "bg-black")
                                                   :style {:width 16 :height 16}}])
               :vector (fn [x options]
                         (->> x
                              (map (partial inspect options))
                              (into [:div.flex.inline-flex])
                              (view-as :hiccup)))
               :list (fn [x options]
                       (->> x
                            (map (partial inspect options))
                            (into [:div.flex.flex-col])
                            (view-as :hiccup)))})]]
  {::dc/state rule-30-state})

(dc/defcard rule-30-child-options
  "Rule 30 using viewers based on viewer options (without overriding global types) and passing the viewer option down to child components."
  [state]
  [:div.result-data
   [inspect (-> @state
                (with-viewer :board)
                (with-viewers
                  {:cell #(view-as :hiccup
                                   [:div.inline-block {:class (if (zero? %)
                                                                "bg-white border-solid border-2 border-black"
                                                                "bg-black")
                                                       :style {:width 16 :height 16}}])
                   :row (fn [x options]
                          (->> x
                               (map #(inspect options (view-as :cell %)))
                               (into [:div.flex.inline-flex])
                               (view-as :hiccup)))
                   :board (fn [x options]
                            (->> x
                                 (map #(inspect options (view-as :row %)))
                                 (into [:div.flex.flex-col])
                                 (view-as :hiccup)))}))]]
  {::dc/state rule-30-state})

(dc/defcard rule-30-html
  "Rule 30 using viewers based on a single viewer rendering the board."
  [state]
  [:div.result-data
   [inspect (with-viewer @state (fn [board]
                                  (let [cell #(vector :div.inline-block
                                                      {:class (if (zero? %)
                                                                "bg-white border-solid border-2 border-black"
                                                                "bg-black")
                                                       :style {:width 16 :height 16}})
                                        row #(into [:div.flex.inline-flex] (map cell) %)]
                                    (html (into [:div.flex.flex-col] (map row) board)))))]]
  {::dc/state rule-30-state})

(dc/defcard rule-30-sci-eval
  "Rule 30 using viewers based on sci eval."
  [inspect (with-viewer '([0 1 0] [1 0 1])
             '(fn [board]
                (let [cell #(vector :div.inline-block
                                    {:class (if (zero? %)
                                              "bg-white border-solid border-2 border-black"
                                              "bg-black")
                                     :style {:width 16 :height 16}})
                      row #(into [:div.flex.inline-flex] (map cell) %)]
                  (v/html (into [:div.flex.flex-col] (map row) board)))))])

(dc/defcard inspect-values
  (into [:div]
        (for [value [123
                     ##NaN
                     'symbol
                     ::keyword
                     "a string"
                     nil
                     true
                     false
                     {:some "map"}
                     '[vector of symbols]
                     '(:list :of :keywords)
                     #js {:js "object"}
                     #js ["array"]
                     (js/Date.)
                     (random-uuid)
                     (fn a-function [foo])
                     (atom "an atom")
                     ^{:nextjournal/tag 'object} ['clojure.lang.Atom 0x2c42b421 {:status :ready, :val 1}]
                     ^{:nextjournal/tag 'var} ['user/a {:foo :bar}]
                     ^{:nextjournal/tag 'object} ['clojure.lang.Ref 0x73aff8f1 {:status :ready, :val 1}]]]
          [:div.mb-3.result-viewer
           [:pre [:code.inspected-value (binding [*print-meta* true] (pr-str value))]] [:span.inspected-value " => "]
           [inspect {} value]])))

(dc/defcard inspect-large-values
  "Defcard for larger datastructures clj and json, we make use of the db viewer."
  [:div]
  (fn []
    (let [gen-keyword #(keyword (gensym))
          generate-ds (fn [x val-fun]
                        (loop [x x res {}]
                          (cond
                            (= x 0) res
                            :else (recur (dec x) (assoc res (gen-keyword) (val-fun))))))
          value-1 (generate-ds 42 gen-keyword)]
      (generate-ds 42 (fn [] (clj->js value-1))))))

(dc/defcard inspect-in-process
  "Different datastructures that live in-process in the browser. More values can just be displayed without needing to fetch more data."
  [:div
   [:div [inspect (range 1000)]]
   [:div [inspect (vec (range 1000))]]
   [:div [inspect (zipmap (range 1000) (range 1000))]]])

(dc/defcard viewer-reagent-atom
  [inspect (reagent/atom {:hello :world})])

(dc/defcard viewer-js-window []
  [inspect js/window])

(dc/defcard viewer-vega-lite
  [inspect (view-as :vega-lite
                    {:width 650
                     :height 400
                     :data
                     {:url "https://vega.github.io/vega-datasets/data/us-10m.json"
                      :format
                      {:type "topojson" :feature "counties"}}
                     :transform
                     [{:lookup "id"
                       :from
                       {:data {:url "https://vega.github.io/vega-datasets/data/unemployment.tsv"}
                        :key "id"
                        :fields ["rate"]}}]
                     :projection {:type "albersUsa"}
                     :mark "geoshape"
                     :encoding
                     {:color {:field "rate" :type "quantitative"}}})])

(dc/defcard viewer-plolty
  [inspect (view-as :plotly
                    {:data [{:y (shuffle (range 10)) :name "The Federation" }
                            {:y (shuffle (range 10)) :name "The Empire"}]})])

(dc/defcard viewer-katex
  [inspect (view-as :latex
                    "G_{\\mu\\nu}\\equiv R_{\\mu\\nu} - {\\textstyle 1 \\over 2}R\\,g_{\\mu\\nu} = {8 \\pi G \\over c^4} T_{\\mu\\nu}")])

(dc/defcard viewer-mathjax
  [inspect (view-as :mathjax
                    "G_{\\mu\\nu}\\equiv R_{\\mu\\nu} - {\\textstyle 1 \\over 2}R\\,g_{\\mu\\nu} = {8 \\pi G \\over c^4} T_{\\mu\\nu}")])

(dc/defcard viewer-markdown
  [:div.viewer-markdown
   [inspect (view-as :markdown "# Supported Markdown

## Paragraphs

To create paragraphs, use a blank line to separate one or more lines of text.

I really like using Markdown.

I think I'll use it to format all of my documents from now on.

## Headings

```
  # Heading level 1
  ## Heading level 2
  ### Heading level 3
  #### Heading level 4
  ##### Heading level 5
  ###### Heading level 6
```

## Inlines

- I just love **bold text**.
- Italicized text is the *cat's meow*.
- This text is ***really important***.
- My favorite search engine is [Duck Duck Go](https://duckduckgo.com).
- At the command prompt, type `acme`.
- This was ~~entirely uninteresting.~~
- The Dow Jones Industrial Average for February 7, 2006 ![Dow Jones Industrial Average for February 7, 2006](https://upload.wikimedia.org/wikipedia/commons/thumb/8/81/Sparkline_dowjones_new.svg/200px-Sparkline_dowjones_new.svg.png).

## Lists

### Ordered

1. First item
2. Second item
3. Third item
  1. Indented item
  2. Indented item
4. Fourth item

### Unordered

- First item
- Second item
- Third item
  - Indented item
  - Indented item
- Fourth item

### Todo

- [x] First item
- [x] Second item
- [ ] Third item
  - [x] Indented item
  - [ ] Indented item
- [ ] Fourth item

## Code Blocks

```json
{
  \"firstName\": \"Mecca\",
  \"lastName\": \"Smith\",
  \"age\": 25
}
```

## Horizontal Rules

---

## Blockquotes

> Dorothy followed her through many of the beautiful rooms in her castle.
>
>> The Witch bade her clean the pots and kettles and sweep the floor and keep the fire fed with wood.

## Images

![_An old rock in the desert_, Shiprock, New Mexico, by Beau Rogers](https://live.staticflickr.com/389/31833779864_38b5c9d52e_c_d.jpg)

## Formulas

$$\\int_{\\omega} \\phi d\\omega$$

## Tables

as building hiccup is recursive, we're using the specific viewers for values occurring at any level in the structure

| Syntax |  JVM                     | JavaScript                                    |
|--------|:------------------------:|----------------------------------------------:|
|   foo  |  Loca _lDate_ ahoiii     | goog.date.Date                                |
|   bar  |  java.time.LocalTime     | somethng else entirey                         |
|   bag  |  java.time.LocalDateTime | $\\bigoplus_{\\alpha < \\omega}\\phi_\\alpha$ |

")]])

(dc/defcard viewer-code
  [inspect (view-as :code "(str (+ 1 2) \"some string\")")])

(dc/defcard viewer-hiccup
  [inspect (view-as :hiccup [:h1 "Hello Hiccup 👋"])])

(dc/defcard viewer-reagent-component
  "A simple counter component in reagent using `reagent.core/with-let`."
  [inspect (view-as :reagent
                    (fn []
                      (reagent/with-let [c (reagent/atom 0)]
                        [:<>
                         [:h2 "Count: " @c]
                         [:button.rounded.bg-blue-500.text-white.py-2.px-4.font-bold.mr-2 {:on-click #(swap! c inc)} "increment"]
                         [:button.rounded.bg-blue-500.text-white.py-2.px-4.font-bold {:on-click #(swap! c dec)} "decrement"]])))])

(dc/defcard progress-bar
  "Show how to use a function as a viewer, supports both one and two artity versions."
  [:div
   [inspect (with-viewer 0.33
              #(view-as :hiccup
                        [:div.relative.pt-1
                         [:div.overflow-hidden.h-2.mb-4-text-xs.flex.rounded.bg-teal-200
                          [:div.shadow-none.flex.flex-col.text-center.whitespace-nowrap.text-white.bg-teal-500
                           {:style {:width (-> %
                                               (* 100)
                                               int
                                               (max 0)
                                               (min 100)
                                               (str "%"))}}]]]))]
   [inspect (with-viewer 0.35
              (fn [v _opts] (view-as :hiccup
                                     [:div.relative.pt-1
                                      [:div.overflow-hidden.h-2.mb-4-text-xs.flex.rounded.bg-teal-200
                                       [:div.shadow-none.flex.flex-col.text-center.whitespace-nowrap.text-white.bg-teal-500
                                        {:style {:width (-> v
                                                            (* 100)
                                                            int
                                                            (max 0)
                                                            (min 100)
                                                            (str "%"))}}]]])))]])
