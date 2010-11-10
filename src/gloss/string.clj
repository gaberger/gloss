;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns gloss.string
  (:use
    [gloss bytes consumer])
  (:import
    [java.nio
     Buffer
     ByteBuffer
     CharBuffer]
    [java.nio.charset
     CharsetDecoder
     Charset
     CoderResult]))

(defn- create-char-buf
  [^CharsetDecoder decoder buf-seq]
  (CharBuffer/allocate (int (Math/ceil (/ (buf-seq-count buf-seq) (.averageCharsPerByte decoder))))))

(defn- nth-char [char-buf-seq length idx]
  (if (neg? idx)
    (throw (IndexOutOfBoundsException. (str idx " is not a valid index.")))
    (loop [idx idx chars chars]
      (let [buf ^CharBuffer (first chars)]
	(cond
	  (nil? buf) (throw (IndexOutOfBoundsException. (str idx "is greater than length of " length)))
	  (> idx (.remaining buf)) (recur (- idx (.remaining buf)) (rest chars))
	  :else (.get buf idx))))))

(defn- sub-sequence [buf-seq length start end]
  (if (or (neg? start) (<= length end))
    (throw (IndexOutOfBoundsException. (str "[" start ", " end ") is not a valid interval.")))
    (-> buf-seq (drop-from-bufs start) (take-from-bufs (- end start)))))

(defn- create-decoder [charset]
  (.newDecoder (Charset/forName charset)))

(defn create-char-buf-seq [chars]
  (let [length (apply + (map #(.remaining ^CharBuffer %) chars))]
    (reify
      
      CharSequence
      (charAt [this idx] (nth-char chars length idx))
      (length [_] length)
      (subSequence [_ start end] (sub-sequence chars length start end))
      (toString [_] (apply str chars))
      
      clojure.lang.Counted
      (count [_] length))))

;;;

(defn- take-finite-string-from-buf-seq [^CharsetDecoder decoder ^CharBuffer char-buf buf-seq]
  (let [buf-seq (dup-buf-seq buf-seq)]
    (if-not (.hasRemaining char-buf)
      [char-buf buf-seq]
      (loop [bytes buf-seq]
	(if (empty? bytes)
	  [char-buf nil]
	  (let [first-buf (first bytes)
		result (.decode decoder first-buf char-buf false)]
	    (cond
	      
	      (.isOverflow result)
	      [char-buf bytes]
	      
	      (and (.isUnderflow result) (pos? (.remaining first-buf)))
	      (if (= 1 (count bytes))
		[char-buf bytes]
		(recur
		  (cons
		    (take-contiguous-bytes (inc (buf-seq-count (take 1 bytes))) bytes)
		    (drop-bytes 1 (rest bytes)))))
	      
	      :else
	      (recur (rest bytes)))))))))

(defn finite-string-consumer- [decoder char-buf]
  (reify
    ByteConsumer
    (feed- [this buf-seq]
      (let [[chars bytes] (take-finite-string-from-buf-seq decoder char-buf buf-seq)]
	(if-not (.hasRemaining chars)
	  [(.rewind ^CharBuffer chars) bytes]
	  [this buf-seq])))))

(defn finite-string-consumer
  [charset len]
  (let [decoder (create-decoder charset)
	char-buf (CharBuffer/allocate len)]
    (finite-string-consumer- decoder char-buf)))

;;;

(defn take-string-from-buf-seq [^CharsetDecoder decoder, buf-seq]
  (let [buf-seq (dup-buf-seq buf-seq)
	char-buf (create-char-buf decoder buf-seq)]
    (loop [chars [char-buf], bytes buf-seq]
      (if (empty? bytes)
	[(rewind-buf-seq chars) nil]
	(let [first-buf (first bytes)
	      result (-> decoder (.decode first-buf (last chars) false))]
	  (cond

	    (.isOverflow result)
	    (recur (conj chars (create-char-buf decoder bytes)) bytes)

	    (and (.isUnderflow result) (pos? (.remaining first-buf)))
	    (if (= 1 (count bytes))
	      [(rewind-buf-seq chars) bytes]
	      (recur chars
		(cons
		  (take-contiguous-bytes (inc (buf-seq-count (take 1 bytes))) bytes)
		  (drop-bytes 1 (rest bytes)))))

	    :else
	    (recur chars (rest bytes))))))))

(defn string-consumer [charset]
  (let [decoder (create-decoder charset)]
    (reify
      ByteConsumer
      (feed- [this buf-seq]
	(let [[chars bytes] (take-string-from-buf-seq decoder buf-seq)]
	  (if (zero? (buf-seq-count chars))
	    [this buf-seq]
	    [(create-char-buf-seq chars) bytes]))))))
