(ns nextjournal.markdown-test
  (:require [clojure.test :refer :all]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]))

(def markdown-text
  "# Hello

some **strong** _assertion_ and a [link] and a $\\pi$ formula

```clojure
(+ 1 2 3)
```

$$\\int_a^bf(t)dt$$

* one
* two

[link]:/path/to/something
")

(deftest parse-test
  (testing "ingests markdown returns nested nodes"
    (is (= {:content [{:content [{:text "Hello"
                                  :type :text}]
                       :heading-level 1
                       :type :heading}
                      {:content [{:text "some "
                                  :type :text}
                                 {:content [{:text "strong"
                                             :type :text}]
                                  :type :strong}
                                 {:text " "
                                  :type :text}
                                 {:content [{:text "assertion"
                                             :type :text}]
                                  :type :em}
                                 {:text " and a "
                                  :type :text}
                                 {:attrs {:href "/path/to/something"}
                                  :content [{:text "link"
                                             :type :text}]
                                  :type :link}
                                 {:text " and a "
                                  :type :text}
                                 {:text "\\pi"
                                  :type :formula}
                                 {:text " formula"
                                  :type :text}]
                       :type :paragraph}
                      {:content [{:text "(+ 1 2 3)\n" :type :text}]
                       :info "clojure"
                       :language "clojure"
                       :type :code}
                      {:text "\\int_a^bf(t)dt"
                       :type :block-formula}
                      {:content [{:content [{:content [{:text "one"
                                                        :type :text}]
                                             :type :paragraph}]
                                  :type :list-item}
                                 {:content [{:content [{:text "two"
                                                        :type :text}]
                                             :type :paragraph}]
                                  :type :list-item}]
                       :type :bullet-list}]
            :toc {:content [{:level 1
                             :node {:content [{:text "Hello" :type :text}]
                                    :heading-level 1
                                    :type :heading}
                             :path [:content 0]
                             :title "Hello"
                             :type :toc}]
                  :type :toc}
            :type :doc}
           (md/parse markdown-text)))))

(deftest ->hiccup-test
  "ingests markdown returns hiccup"
  (is (= [:div
          [:h1 {:id "Hello"} "Hello"]
          [:p
           "some "
           [:strong
            "strong"]
           " "
           [:em
            "assertion"]
           " and a "
           [:a
            {:href "/path/to/something"}
            "link"]
           " and a "
           [:span.formula
            "\\pi"]
           " formula"]
          [:pre.viewer-code "(+ 1 2 3)\n"]
          [:figure.formula
           "\\int_a^bf(t)dt"]
          [:ul
           [:li
            [:p
             "one"]]
           [:li
            [:p
             "two"]]]]
         (md/->hiccup markdown-text))))


(deftest ->hiccup-toc-test
  "Builds Toc"

  (let [md "# Title

## Section 1

[[TOC]]

## Section 2

### Section 2.1
"
        data (md/parse md)
        hiccup (md.transform/->hiccup data)]

    (is (= {:content [{:content [{:text "Title"
                                  :type :text}]
                       :heading-level 1
                       :type :heading}
                      {:content [{:text "Section 1"
                                  :type :text}]
                       :heading-level 2
                       :type :heading}
                      {:type :toc}
                      {:content [{:text "Section 2"
                                  :type :text}]
                       :heading-level 2
                       :type :heading}
                      {:content [{:text "Section 2.1"
                                  :type :text}]
                       :heading-level 3
                       :type :heading}]
            :toc {:content [{:content [{:level 2
                                        :node {:content [{:text "Section 1"
                                                          :type :text}]
                                               :heading-level 2
                                               :type :heading}
                                        :path [:content
                                               1]
                                        :title "Section 1"
                                        :type :toc}
                                       {:content [{:level 3
                                                   :node {:content [{:text "Section 2.1"
                                                                     :type :text}]
                                                          :heading-level 3
                                                          :type :heading}
                                                   :path [:content
                                                          4]
                                                   :title "Section 2.1"
                                                   :type :toc}]
                                        :level 2
                                        :node {:content [{:text "Section 2"
                                                          :type :text}]
                                               :heading-level 2
                                               :type :heading}
                                        :path [:content
                                               3]
                                        :title "Section 2"
                                        :type :toc}]
                             :level 1
                             :node {:content [{:text "Title"
                                               :type :text}]
                                    :heading-level 1
                                    :type :heading}
                             :path [:content
                                    0]
                             :title "Title"
                             :type :toc}]
                  :type :toc}
            :type :doc}
           data))

    (is (= [:div
            [:h1
             {:id "Title"}
             "Title"]
            [:h2
             {:id "Section%201"}
             "Section 1"]
            [:div.toc
             [:div
              nil
              [:ul
               [:li.toc-item
                [:div
                 [:a
                  {:href "#Title"}
                  [:h1
                   "Title"]]
                 [:ul
                  [:li.toc-item
                   [:div
                    [:a
                     {:href "#Section%201"}
                     [:h2
                      "Section 1"]]
                    nil]]
                  [:li.toc-item
                   [:div
                    [:a
                     {:href "#Section%202"}
                     [:h2
                      "Section 2"]]
                    [:ul
                     [:li.toc-item
                      [:div
                       [:a
                        {:href "#Section%202.1"}
                        [:h3
                         "Section 2.1"]]
                       nil]]]]]]]]]]]
            [:h2
             {:id "Section%202"}
             "Section 2"]
            [:h3
             {:id "Section%202.1"}
             "Section 2.1"]]
          hiccup))))

(deftest todo-lists
  (testing "todo lists"
    (is (= [:div
            [:h1 {:id "Todos"} "Todos"]
            [:ul.contains-task-list
             [:li
              [:input
               {:checked true
                :type "checkbox"}]
              [:p
               "checked"]]
             [:li
              [:input
               {:checked false
                :type "checkbox"}]
              [:p
               "unchecked"]
              [:ul.contains-task-list
               [:li
                [:input
                 {:checked false
                  :type "checkbox"}]
                [:p
                 "nested"]]]]]]
           (md/->hiccup "# Todos
- [x] checked
- [ ] unchecked
  - [ ] nested
")))))

(deftest tags-text
  (testing "parsing tags"
    (is (= {:content [{:content [{:text "Hello Tags"
                                  :type :text}]
                       :heading-level 1
                       :type :heading}
                      {:content [{:text "par with "
                                  :type :text}
                                 {:text "really_nice"
                                  :type :hashtag}
                                 {:text " "
                                  :type :text}
                                 {:text "useful-123"
                                  :type :hashtag}
                                 {:text " tags"
                                  :type :text}]
                       :type :paragraph}]
            :toc {:content [{:level 1
                             :node {:content [{:text "Hello Tags"
                                               :type :text}]
                                    :heading-level 1
                                    :type :heading}
                             :path [:content
                                    0]
                             :title "Hello Tags"
                             :type :toc}]
                  :type :toc}
            :type :doc}
           (md/parse "# Hello Tags
par with #really_nice #useful-123 tags
"))))

  (testing "rendering tags"
    (is (= [:div
            [:h1 {:id "Hello%20Tags"} "Hello Tags"]
            [:p
             "par with "
             [:a.tag
              {:href "/tags/really_nice"}
              "#really_nice"]
             " "
             [:a.tag
              {:href "/tags/useful-123"}
              "#useful-123"]
             " tags"]]
           (md/->hiccup "# Hello Tags
par with #really_nice #useful-123 tags
")))))

(comment
  (run-tests 'nextjournal.markdown-test)
  )
