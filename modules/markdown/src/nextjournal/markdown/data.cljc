(ns nextjournal.markdown.data
  "Transforms a collection of markdown-it tokens obtained from parsing markdown text into a nested structure composed
   of nodes.

  Node:
  - type: a Node's type as keyword (:heading, :paragraph, :text, etc.)
  - info: (optional) fenced code info
  - content: (optional) a collection of Nodes representing nested content
  - text: (optional) content of text nodes, a collection of Nodes
  - marks: (optional) in text nodes, a collection of marks insisting on the node's text
  - level: (optional) heading level
  - attrs: attributes as passed by markdownit tokens (e.g {:attrs {:style \"some style info\"}})

  Mark:
  - mark: the mark type (:em, :strong, :link, etc.)
  - attrs: mark attributes e.g. href in links
  ")

;; region node operations
(defn pairs->kmap [pairs] (into {} (map (juxt (comp keyword first) second)) pairs))
(defn inc-last [path] (update path (dec (count path)) inc))
(defn hlevel [{:as _token hn :tag}] (when (string? hn) (some-> (re-matches #"h([\d])" hn) second #?(:clj read-string :cljs cljs.reader/read-string))))
(defn ->text [{:as _node :keys [text formula content]}] (or text formula (apply str (map ->text content))))

(defn node
  [type content attrs top-level]
  (cond-> {:type type :content content}
    (seq attrs) (assoc :attrs (pairs->kmap attrs))
    (seq top-level) (merge top-level)))
(defn mark
  ([type] (mark type nil))
  ([type attrs] (cond-> {:mark type} (seq attrs) (assoc :attrs (pairs->kmap attrs)))))
(defn text-node
  ([text] (text-node text nil))
  ([text marks] (cond-> {:type :text :text text} (seq marks) (assoc :marks marks))))
(defn formula [text] {:type :formula :text text})

(defn empty-text-node? [{text :text t :type}] (and (= :text t) (empty? text)))

(defn push-node [{:as doc ::keys [path]} node]
  (cond-> doc
    (not (empty-text-node? node)) ;; ⬅ mdit produces empty text tokens at mark boundaries, see edge cases below
    (-> #_doc
        (update ::path inc-last)
        (update-in (pop path) conj node))))

(defn open-node
  ([doc type] (open-node doc type {}))
  ([doc type attrs] (open-node doc type attrs {}))
  ([doc type attrs top-level]
   (-> doc
       (push-node (node type [] attrs top-level))
       (update ::path into [:content -1]))))

;; after closing a node, document ::path will point at it
(defn close-node [doc] (update doc ::path (comp pop pop)))

(defn open-mark
  ([doc type] (open-mark doc type {}))
  ([doc type attrs]
   (update doc ::marks conj (mark type attrs))))

(defn close-mark [doc] (update doc ::marks pop))

(comment                                                    ;; path after call
  (-> empty-doc                                             ;; [:content -1]
      (open-node :heading)                                  ;; [:content 0 :content -1]
      (push-node {:node/type :text :text "foo"})            ;; [:content 0 :content 0]
      (push-node {:node/type :text :text "foo"})            ;; [:content 0 :content 1]
      close-node                                            ;; [:content 1]

      (open-node :paragraph)                                ;; [:content 1 :content]
      (push-node {:node/type :text :text "hello"})
      close-node
      (open-node :bullet-list)
      ;;
      ))
;; endregion

;; region TOC builder: (`add-to-toc` acts on toc after closing a header node)
(declare ->hiccup)
(defn add-to-toc [{:as doc :keys [toc] path ::path}]
  (let [{:as h :keys [heading-level]} (get-in doc path)
        ;; credits for update fns @plexus
        update-last-child (fn [m f & args]
                            (let [m (if (empty? (:children m))
                                      (update m :children conj {:children []})
                                      m)]
                              (apply update-in m
                                     [:children (dec (count (:children m)))]
                                     f args)))
        update-level (fn update-level [m lvl f & args]
                       (if (= lvl 1)
                         (apply f m args)
                         (apply update-last-child m update-level (dec lvl) f args)))]
    (cond-> doc
      (integer? heading-level)
      (update :toc update-level heading-level update :children conj {:level heading-level
                                                                     :title (->text h)
                                                                     :title-hiccup (->hiccup h)
                                                                     :children []
                                                                     :path path}))))

(comment
  (-> "# Start

dadang bang

### Three

## Two

par
- and a nested
- ### Heading not included

foo

## Two Again

par

# One Again

#### Four

end"
      nextjournal.markdown/parse
      <-tokens
      :toc
      ))
;; endregion

;; region token handlers
(declare apply-tokens)
(defmulti apply-token (fn [_doc token] (:type token)))

;; blocks
(defmethod apply-token "heading_open" [doc token] (open-node doc :heading {} {:heading-level (hlevel token)}))
(defmethod apply-token "heading_close" [doc {doc-level :level}] (-> doc close-node (cond-> (zero? doc-level) add-to-toc)))
;; for building the TOC we just care about headings at document top level (not e.g. nested under lists) ⬆

(defmethod apply-token "paragraph_open" [doc _token] (open-node doc :paragraph))
(defmethod apply-token "paragraph_close" [doc _token] (close-node doc))

(defmethod apply-token "bullet_list_open" [doc _token] (open-node doc :bullet-list))
(defmethod apply-token "bullet_list_close" [doc _token] (close-node doc))

(defmethod apply-token "ordered_list_open" [doc _token] (open-node doc :numbered-list))
(defmethod apply-token "ordered_list_close" [doc _token] (close-node doc))

(defmethod apply-token "list_item_open" [doc _token] (open-node doc :list-item))
(defmethod apply-token "list_item_close" [doc _token] (close-node doc))

(defmethod apply-token "math_block" [doc {text :content}] (-> doc (open-node :block-formula) (push-node (formula text))))
(defmethod apply-token "math_block_end" [doc _token] (close-node doc))

(defmethod apply-token "hr" [doc _token] (push-node doc {:type :ruler}))

(defmethod apply-token "blockquote_open" [doc _token] (open-node doc :blockquote))
(defmethod apply-token "blockquote_close" [doc _token] (close-node doc))

(defmethod apply-token "code_block" [doc {:as _token c :content}]
  (-> doc
      (open-node :code)
      (push-node (text-node c))
      close-node))
(defmethod apply-token "fence" [doc {:as _token i :info c :content}]
  (-> doc
      (open-node :code {:info i})
      (push-node (text-node c))
      close-node))

;; tables
;; table data tokens might have {:style "text-align:right|left"} attrs, maybe better nested node > :attrs > :style ?
(defmethod apply-token "table_open" [doc _token] (open-node doc :table))
(defmethod apply-token "table_close" [doc _token] (close-node doc))
(defmethod apply-token "thead_open" [doc _token] (open-node doc :table-head))
(defmethod apply-token "thead_close" [doc _token] (close-node doc))
(defmethod apply-token "tr_open" [doc _token] (open-node doc :table-row))
(defmethod apply-token "tr_close" [doc _token] (close-node doc))
(defmethod apply-token "th_open" [doc token] (open-node doc :table-header (:attrs token)))
(defmethod apply-token "th_close" [doc _token] (close-node doc))
(defmethod apply-token "tbody_open" [doc _token] (open-node doc :table-body))
(defmethod apply-token "tbody_close" [doc _token] (close-node doc))
(defmethod apply-token "td_open" [doc token] (open-node doc :table-data (:attrs token)))
(defmethod apply-token "td_close" [doc _token] (close-node doc))

(comment
  (->
"
| Syntax |  JVM                     | JavaScript                      |
|--------|-------------------------:|:--------------------------------|
|   foo  |  Loca _lDate_ ahoiii     | goog.date.Date                  |
|   bar  |  java.time.LocalTime     | some [kinky](link/to/something) |
|   bag  |  java.time.LocalDateTime | $\\phi$                         |
"
    nextjournal.markdown/parse-j
    nextjournal.markdown.data/<-tokens
    nextjournal.markdown.data/->hiccup
    ))

;; inlines
(defmethod apply-token "inline" [doc {:as _token ts :children}] (apply-tokens doc ts))

(defmethod apply-token "text" [{:as doc ms ::marks} {text :content}] (push-node doc (text-node text ms)))

(defmethod apply-token "math_inline" [doc {text :content}] (push-node doc (formula text)))
(defmethod apply-token "math_inline_double" [doc {text :content}] (push-node doc (formula text)))

(defmethod apply-token "softbreak" [doc {text :content}] (push-node doc {:type :softbreak}))

;; marks
(defmethod apply-token "em_open" [doc _token] (open-mark doc :em))
(defmethod apply-token "em_close" [doc _token] (close-mark doc))
(defmethod apply-token "strong_open" [doc _token] (open-mark doc :strong))
(defmethod apply-token "strong_close" [doc _token] (close-mark doc))
(defmethod apply-token "s_open" [doc _token] (open-mark doc :strikethrough))
(defmethod apply-token "s_close" [doc _token] (close-mark doc))
(defmethod apply-token "link_open" [doc token] (open-mark doc :link (:attrs token)))
(defmethod apply-token "link_close" [doc _token] (close-mark doc))
(defmethod apply-token "code_inline" [{:as doc ms ::marks} {text :content}]
  (push-node doc (text-node text (conj ms (mark :monospace)))))
;; endregion

;; region data builder api
(def apply-tokens (partial reduce apply-token))

(def empty-doc {:type :doc
                :content []
                :toc {:children []}
                ::path [:content -1] ::marks []})

(defn <-tokens
  "Takes a doc and a collection of markdown-it tokens, applies tokens to doc. Uses an emtpy doc in arity 1."
  ([tokens] (<-tokens empty-doc tokens))
  ([doc tokens] (-> doc (apply-tokens tokens) (dissoc ::path ::marks))))

(comment
  (-> "# Markdown Data

some _emphatic_ **strong** [link](https://foo.com)

---

> some ~~nice~~ quote
> for fun

## Formulas

$$\\Pi^2$$

* and
* some $\\Phi_{\\alpha}$ latext
* bullets

## Fences

```py id=\"aaa-bbb-ccc\"
1
print(\"this is some python\")
2
3
```

Hline Section
-------------

### but also indented code

    import os
    os.listdir('/')

or monospace mark [`real`](/foo/bar) fun
"
      nextjournal.markdown/parse
      nextjournal.markdown.data/<-tokens
      ;;seq
      ;;(->> (take 10))
      ;;(->> (take-last 4))
      )

  ;; Edge Cases
  ;; * overlapping marks produce empty text nodes
  (-> "*some text **with*** **mark overlap**"
      nextjournal.markdown/parse
      second
      :children
      seq
      )

  ;; * a strong with __ produces surrounding empty text nodes:
  (-> "__text__" nextjournal.markdown/parse second :children  seq)
  (-> "__text__" nextjournal.markdown/parse <-tokens))
;; endregion

;; region hiccup renderer (maybe move to .hiccup ns)
(declare node->hiccup)
(defn wrap-content [ctx hiccup node] (into hiccup (map (partial node->hiccup ctx)) (:content node)))
(defn viewer-with-default [{:keys [viewers]} {:as node :keys [type]} hc]
  ;; we might want to let the viewer decide what to extract from node
  (if-some [v (get viewers type)] [v (->text node)] (conj hc (->text node))))

(defmulti  node->hiccup (fn [_ctx {:as _node :keys [type]}] type))
;; blocks
(defmethod node->hiccup :doc [ctx node]           (wrap-content ctx [:div.viewer-markdown] node))
(defmethod node->hiccup :heading [ctx node]       (wrap-content ctx [(keyword (str "h" (:heading-level node)))] node))
(defmethod node->hiccup :paragraph [ctx node]     (wrap-content ctx [:p] node))
(defmethod node->hiccup :block-formula [ctx node] (wrap-content ctx [:figure.formula] node))
(defmethod node->hiccup :bullet-list [ctx node]   (wrap-content ctx [:ul.list-disc.disc-inside] node))
(defmethod node->hiccup :numbered-list [ctx node] (wrap-content ctx [:ol] node))
(defmethod node->hiccup :list-item [ctx node]     (wrap-content ctx [:li] node))
(defmethod node->hiccup :blockquote [ctx node]    (wrap-content ctx [:blockquote] node))
(defmethod node->hiccup :code [ctx node]          (viewer-with-default ctx node [:pre.viewer-code]))

;; inlines
(declare apply-marks apply-mark)
(defmethod node->hiccup :formula [ctx node] (viewer-with-default ctx node [:span.formula]))
(defmethod node->hiccup :text [{:keys [code?]} {:keys [text marks]}]
  (cond-> text (seq marks) (apply-marks marks)))

;; tables
(defmethod node->hiccup :table [ctx node]          (wrap-content ctx [:table] node))
(defmethod node->hiccup :table-head [ctx node]     (wrap-content ctx [:thead] node))
(defmethod node->hiccup :table-body [ctx node]     (wrap-content ctx [:tbody] node))
(defmethod node->hiccup :table-row [ctx node]      (wrap-content ctx [:tr] node))
(defmethod node->hiccup :table-header [ctx node]   (wrap-content ctx [:th] node))
(defmethod node->hiccup :table-data [ctx node]     (wrap-content ctx [:td] node))

;; marks
(defn apply-marks [ret m] (reduce apply-mark ret m))
(defmulti  apply-mark (fn [_hiccup {m :mark}] m))
(defmethod apply-mark :em [hiccup _]                 [:em hiccup])
(defmethod apply-mark :monospace [hiccup _]          [:code hiccup])
(defmethod apply-mark :strong [hiccup _]             [:strong hiccup])
(defmethod apply-mark :strikethrough [hiccup _]      [:s hiccup])
(defmethod apply-mark :link [hiccup {:keys [attrs]}] [:a {:href (:href attrs)} hiccup])

(defn ->hiccup
  "an optional first `ctx` allows for customizing style per node"
  ([node] (->hiccup node {}))
  ([node ctx] (node->hiccup ctx node)))

(comment
  (-> "# Hello

A nice $\\phi$ formula [for _real_ **strong** fun](/path/to)

- one **ahoi** list
- two `nice` and ~~three~~

> that said who?

```clj
(some nice clojure)

```"
      nextjournal.markdown/parse-j
      nextjournal.markdown.data/<-tokens
      ->hiccup
      )
  )
;; endregion

;; region zoom into sections
(defn section-at [{:as doc :keys [content]} [_ pos :as path]]
  ;; TODO: generalize over path (zoom-in at)
  ;; supports only top-level headings atm (as found in TOC)
  (let [{:as h section-level :heading-level} (get-in doc path)
        in-section? (fn [{l :heading-level}] (or (not l) (< section-level l)))]
    (when section-level
      {:type :doc
       :content (cons h
                      (->> content
                           (drop (inc pos))
                           (take-while in-section?)))})))

(comment
  (some-> "# Title

## Section 1

foo

- # What is this? (no!)
- maybe

### Section 1.2

## Section 2

some par

### Section 2.1

some other par

### Section 2.2

#### Section 2.2.1

two two one

#### Section 2.2.2

two two two

## Section 3

some final par"
      nextjournal.markdown/parse-j
      nextjournal.markdown.data/<-tokens
      (section-at [:content 9]) ;; ⬅ paths are stored in TOC sections
      ->hiccup))

;; endregion
(comment
  ;; boot browser repl
  (require '[shadow.cljs.devtools.api :as shadow])
  (shadow/repl :browser)
  )
