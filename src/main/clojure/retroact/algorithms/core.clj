(ns retroact.algorithms.core)

(defn- calculate-lcs-matrix-and-paths [v1 v2]
  (loop [i 0
         j 0
         lcs-matrix (vec (repeat (inc (count v1)) (vec (repeat (inc (count v2)) 0))))
         lcs-paths (vec (repeat (inc (count v1)) (vec (conj (repeat (count v2) [0 -1]) [-1 0]))))]
    (cond
      (>= j (count v2)) [lcs-matrix lcs-paths]
      (>= i (count v1)) (recur 0 (inc j) lcs-matrix lcs-paths)
      (= (get v1 i) (get v2 j)) (recur (inc i) j
                                       (assoc-in lcs-matrix [(inc i) (inc j)] (inc (get-in lcs-matrix [i j])))
                                       (assoc-in lcs-paths [(inc i) (inc j)] [-1 -1]))
      (> (get-in lcs-matrix [i (inc j)]) (get-in lcs-matrix [(inc i) j]))
      (recur (inc i) j
             (assoc-in lcs-matrix [(inc i) (inc j)] (get-in lcs-matrix [i (inc j)]))
             (assoc-in lcs-paths [(inc i) (inc j)] [-1 0]))
      :else (recur (inc i) j (assoc-in lcs-matrix [(inc i) (inc j)] (get-in lcs-matrix [(inc i) j]))
                   lcs-paths))))

(defn calculate-lcs
  "Implemented from https://www.cc.gatech.edu/classes/cs3158_98_fall/lcs.html
  i and j go from zero to (count v1) and (count v2), respectively. However, the lcs-matrix goes to one more than
  (count v1) and (count v2) in each dimension because it needs a row and column of zeros as the start. Therefore,
  indexing into lcs-matrix (and lcs-paths) will be i+1 and j+1 compared to indexing into v1 and v2, which is just
  i and j."
  [v1 v2]
  (let [[lcs-matrix lcs-paths] (calculate-lcs-matrix-and-paths v1 v2)]
    (loop [lcs '() i (count v1) j (count v2)]
      (let [direction (get-in lcs-paths [i j])]
        (cond
          (or (<= i 0) (<= j 0)) (vec lcs)
          (= [-1 -1] direction) (recur (conj lcs (get v1 (dec i))) (dec i) (dec j))
          (= [-1 0] direction) (recur lcs (dec i) j)
          (= [0 -1] direction) (recur lcs i (dec j)))))))

(defn calculate-patch-operations
  "Return two vecs of operations that can be performed on v1 to yield v2. This will be a minimum set of operations. The
  LCS of v1 and v2 will remain untouched and elements around will be removed then inserted as necessary. A caller may
  wish to keep a set of the elements removed because they may be inserted during a later operation. This is particularly
  useful if the elements are costly to regenerate or there are onscreen components mirroring them.

  The first vec returned is a list of removals that must be done in order. The second is a list insertions that must be
  done in order and after the removals."
  [v1 v2]
  (let [[lcs-matrix lcs-paths] (calculate-lcs-matrix-and-paths v1 v2)
        [remove-ops insert-ops]
        (loop [remove-ops [] insert-ops [] i (count v1) j (count v2)]
          (let [direction (get-in lcs-paths [i j])]
            (cond
              (and (<= i 0) (<= j 0)) [remove-ops insert-ops]
              (= [-1 -1] direction) (recur remove-ops insert-ops (dec i) (dec j))
              (= [-1 0] direction) (recur (conj remove-ops [:remove (dec i) (get v1 (dec i))]) insert-ops (dec i) j)
              (and (= [0 -1] direction)) (recur remove-ops (conj insert-ops [:insert i (get v2 (dec j))]) i (dec j)))))]
    (loop [adjusted-insert-ops insert-ops io-index (dec (count insert-ops)) ro-index (dec (count remove-ops)) offset 0]
      (let [insert-index (get-in insert-ops [io-index 1])
            remove-index (get-in remove-ops [ro-index 1])]
        (cond
          (or (< io-index 0) (< ro-index 0)) [remove-ops adjusted-insert-ops]
          (< remove-index insert-index) (recur adjusted-insert-ops io-index (dec ro-index) (inc offset))
          :else (recur (update-in adjusted-insert-ops [io-index 1] - offset) (dec io-index) ro-index offset))))))
