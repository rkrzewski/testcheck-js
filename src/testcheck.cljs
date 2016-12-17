(require '[clojure.test.check :as tc])
(require '[clojure.test.check.generators :as gen])
(require '[clojure.test.check.properties :as prop])
(use '[clojure.set :only (rename-keys)])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^{:export Generator :jsdoc ["@constructor"]} $Generator
  [gen]
  (if (not (gen/generator? gen))
    (throw (js/Error. "Generator cannot be constructed directly.")))
  (this-as this (js/Object.defineProperty this "__clj_gen" #js{ "value" gen })))

(def ^{:jsdoc ["@this {Element}"]} Generator (js* "this.Generator"))

;; Private helpers

(defn- jsgen?
  [x]
  (gen/generator? (aget x "__clj_gen")))

(defn- unwrap
  [x]
  (if (jsgen? x)
    (aget x "__clj_gen")
    (if (gen/generator? x)
      x
      (gen/return x))))

(def warned-map #js{})
(defn deprecated!
  [msg]
  (when-not (aget warned-map msg)
    (aset warned-map msg true)
    (js/console.warn "DEPRECATED" msg (aget (js/Error.) "stack"))))

;; API

(defn ^{:export check} check
  [property options]
  (let [opt (or options (js-obj))
        num-tests (or (aget opt "times") 100)
        max-size (or (aget opt "maxSize") 200)
        seed (aget opt "seed")
        result (tc/quick-check num-tests property :max-size max-size :seed seed)
        resultRenamed (rename-keys result {:failing-size :failingSize :num-tests :numTests})
        resultRenamedDeep (if (contains? resultRenamed :shrunk)
                            (update
                              resultRenamed
                              :shrunk
                              rename-keys
                              {:total-nodes-visited :totalNodesVisited})
                            resultRenamed)]
    (clj->js resultRenamedDeep)))

(defn ^{:export property} property
  [args function]
  (prop/for-all* (map unwrap args) function))

(defn ^{:export sample} sample
  [generator times]
  (deprecated! "Use generator.sample() instead of sample(generator)")
  (let [num-samples (or times 10)]
    (to-array
      (gen/sample (unwrap generator) num-samples))))

; Prototype version
(js/goog.exportSymbol "Generator.prototype.sample" (fn
  [times]
  (this-as this
    (let [num-samples (or times 10)]
      (to-array
        (gen/sample (unwrap this) num-samples))))))

;; Private helpers

(defn- gen-nested-or-val
  [collection-gen val-gen]
  (gen/sized (fn [size]
    (if (zero? size)
      val-gen
      (gen/one-of [
        val-gen
        (unwrap (collection-gen
          (gen/resize
            (quot size 2)
            (gen-nested-or-val collection-gen val-gen))))])))))

(defn- to-object
  [from-seq]
  (let [obj (js-obj)]
    (doseq [[k v] from-seq] (aset obj k v))
    obj))

(defn- gen-obj
  [key-gen val-gen]
  (gen/fmap to-object (gen/vector (gen/tuple key-gen val-gen))))


;; Generator Builders

(js/goog.exportSymbol "gen.suchThat" (fn
  [pred gen]
  (deprecated! "Use generator.where() instead of gen.suchThat(generator)")
  (Generator. (gen/such-that pred (unwrap gen)))))
(js/goog.exportSymbol "gen.notEmpty" (fn
  [gen]
  (Generator. (gen/such-that (comp not-empty js->clj) (unwrap gen)))))

; Prototype version of "suchThat"
(js/goog.exportSymbol "Generator.prototype.where" (fn
  [pred]
  (this-as this (Generator. (gen/such-that pred (unwrap this))))))


(js/goog.exportSymbol "gen.map" (fn
  [gen f]
  (deprecated! "Use generator.then() instead of gen.map(generator)")
  (Generator. (gen/fmap f (unwrap gen)))))
(js/goog.exportSymbol "gen.bind" (fn
  [gen f]
  (deprecated! "Use generator.then() instead of gen.bind(generator)")
  (Generator. (gen/bind (unwrap gen) (fn [value] (unwrap (f value)))))))

; Prototype version of "bind"
(js/goog.exportSymbol "Generator.prototype.then" (fn
  [f]
  (this-as this (Generator. (gen/bind (unwrap this) (fn [value] (unwrap (f value))))))))


(js/goog.exportSymbol "gen.resize" (fn
  [size gen]
  (Generator. (gen/resize size (unwrap gen)))))
(js/goog.exportSymbol "gen.noShrink" (fn
  [gen]
  (Generator. (gen/no-shrink (unwrap gen)))))
(js/goog.exportSymbol "gen.shrink" (fn
  [gen]
  (Generator. (gen/shrink-2 (unwrap gen)))))


;; Simple Generators

(js/goog.exportSymbol "gen.return" (fn
  [value]
  (Generator. (gen/return value))))
(js/goog.exportSymbol "gen.returnOneOf" (fn
  [values]
  (Generator. (gen/elements values))))
(js/goog.exportSymbol "gen.returnOneOfWeighted" (fn
  [pairs]
  (Generator. (gen/frequency (map (fn [[weight, value]] (array weight (gen/return value))) pairs)))))

(js/goog.exportSymbol "gen.oneOf" (fn
  [gens]
  (Generator. (gen/one-of (map unwrap gens)))))
(js/goog.exportSymbol "gen.oneOfWeighted" (fn
  [pairs]
  (Generator. (gen/frequency (map (fn [[weight, gen]] (array weight (unwrap gen))) pairs)))))

(js/goog.exportSymbol "gen.nested" (fn
  [collection-gen val-gen]
  (collection-gen (gen-nested-or-val collection-gen (unwrap val-gen)))))

;; Array and Object

(defn genArray
  ([val-gen min-elements max-elements]
    (gen/fmap to-array (gen/vector (unwrap val-gen) min-elements max-elements)))
  ([val-gen num-elements]
    (gen/fmap to-array (gen/vector (unwrap val-gen) num-elements)))
  ([val-gen-or-arr]
    (gen/fmap to-array
      (if (js/Array.isArray val-gen-or-arr)
        (apply gen/tuple (map unwrap val-gen-or-arr))
        (gen/vector (unwrap val-gen-or-arr))))))

(defn genObject
  ([key-gen val-gen]
    (gen/fmap clj->js (gen-obj (unwrap key-gen) (unwrap val-gen))))
  ([val-gen-or-obj]
    (if (= js/Object (.-constructor val-gen-or-obj))
      (let [obj val-gen-or-obj
            seq (into {} (for [k (js-keys obj)] [k (unwrap (aget obj k))]))
        ks (keys seq)
        vs (vals seq)]
        (gen/fmap clj->js
          (gen/fmap (partial zipmap ks)
                    (apply gen/tuple vs))))
      (gen-obj (gen/not-empty gen/string-alphanumeric) (unwrap val-gen-or-obj)))))

(defn ^{:export gen.arrayOrObject} genArrayOrObject
  [val-gen]
  (gen/one-of [(genArray val-gen) (genObject val-gen)]))

(js/goog.exportSymbol "gen.array" (fn [& args] (Generator. (apply genArray args))))
(js/goog.exportSymbol "gen.object" (fn [& args] (Generator. (apply genObject args))))
(js/goog.exportSymbol "gen.arrayOrObject" (fn [val-gen] (Generator. (genArrayOrObject (unwrap val-gen)))))

;; JS Primitives

(js/goog.exportSymbol "gen.NaN" (Generator. (gen/return js/NaN)))
(js/goog.exportSymbol "gen.undefined" (Generator. (gen/return js/undefined)))
(js/goog.exportSymbol "gen.null" (Generator. (gen/return nil)))
(js/goog.exportSymbol "gen.boolean" (Generator. gen/boolean))

(js/goog.exportSymbol "gen.number" (Generator. gen/double))
(js/goog.exportSymbol "gen.posNumber" (Generator. (gen/double* {:min 0, :NaN? false})))
(js/goog.exportSymbol "gen.negNumber" (Generator. (gen/double* {:max 0, :NaN? false})))
(js/goog.exportSymbol "gen.numberWithin" (fn [from, to]
  (Generator. (gen/double* {:min from, :max to, :NaN? false}))))

(js/goog.exportSymbol "gen.int" (Generator. gen/int))
(js/goog.exportSymbol "gen.posInt" (Generator. gen/pos-int))
(js/goog.exportSymbol "gen.negInt" (Generator. gen/neg-int))
(js/goog.exportSymbol "gen.strictPosInt" (Generator. gen/s-pos-int))
(js/goog.exportSymbol "gen.strictNegInt" (Generator. gen/s-neg-int))
(js/goog.exportSymbol "gen.intWithin" (fn [lower, upper]
  (Generator. (gen/choose lower upper))))

(js/goog.exportSymbol "gen.char", (Generator. gen/char))
(js/goog.exportSymbol "gen.asciiChar" (Generator. gen/char-ascii))
(js/goog.exportSymbol "gen.alphaNumChar" (Generator. gen/char-alphanumeric))

(js/goog.exportSymbol "gen.string" (Generator. gen/string))
(js/goog.exportSymbol "gen.asciiString" (Generator. gen/string-ascii))
(js/goog.exportSymbol "gen.alphaNumString" (Generator. gen/string-alphanumeric))


;; JSON
(def genJSONPrimitive (gen/frequency [
  [1 (gen/return nil)]
  [2 gen/boolean]
  [3 (gen/double* {:infinite? false, :NaN? false})]
  [10 gen/int]
  [10 gen/string]]))
(def genJSONValue (gen-nested-or-val genArrayOrObject genJSONPrimitive))

(js/goog.exportSymbol "gen.JSONPrimitive" (Generator. genJSONPrimitive))
(js/goog.exportSymbol "gen.JSONValue" (Generator. genJSONValue))
(js/goog.exportSymbol "gen.JSON" (Generator. (genObject genJSONValue)))


;; JS values, potentially nested

(def genPrimitive (gen/frequency [
  [1 (gen/return js/undefined)]
  [2 (gen/return nil)]
  [4 gen/boolean]
  [6 gen/double]
  [20 gen/int]
  [20 gen/string]]))

(js/goog.exportSymbol "gen.primitive" (Generator. genPrimitive))
(js/goog.exportSymbol "gen.any"
  (Generator. (gen-nested-or-val genArrayOrObject genPrimitive)))
