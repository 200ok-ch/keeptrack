#!/usr/bin/env bb

;; uses curl, cmp, and date

(ns keeptrack
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [shell-smith.core :as smith]))

(def usage "
keeptrack - Keep track of changes with a clean versioned history

Usage:
  keeptrack [options] <url>

Options:
  -d, --debug              Print some debug messages
  -h, --handler=<handler>  Command to execute in case a new version was created
  -s, --storage=<storage>  Path to keep the stored files [default: .]
  -t, --tempdir=<tempdir>  Path for temporary files [default: /tmp/keeptrack]

Handler Substitutions:
  %o  old file
  %n  new file
")

(defn get-version [filename]
  (if-let [version-match (and filename (re-find #".*-v(\d+)\." filename))]
    (Integer/parseInt (second version-match))
    0))

(defn files-equal? [file1 file2]
  (let [result (shell/sh "cmp" "-s" file1 file2)]
    (= 0 (:exit result))))

(defn fetch-url [url temp-file]
  (let [result (shell/sh "curl" "-s" url "-o" temp-file)]
    (= 0 (:exit result))))

(defn create-timestamp []
  (let [result (shell/sh "date" "-u" "+%Y%m%dT%H%M%SZ")]
    (str/trim (:out result))))

(defn -main [& args]
  (let [{:keys [url storage tempdir debug handler] :as config}
        (-> (smith/config usage)
            (update :storage fs/expand-home))
        date (create-timestamp)
        latest-symlink (str storage "/latest.json")
        latest-file (when (fs/exists? latest-symlink)
                      (str storage "/" (fs/read-link latest-symlink)))
        temp-file (str tempdir "/" (System/currentTimeMillis))]

    (fs/create-dirs tempdir)

    ;; Fetch the document
    (when-not (fetch-url url temp-file)
      (println (str "Error: Failed to fetch content from " url))
      (fs/delete-if-exists temp-file)
      (System/exit 1))

    (when debug
      (println "Latest file" latest-file))

    ;; Compare with latest version if it exists
    (when (and (fs/exists? latest-file)
               (fs/regular-file? latest-file)
               (files-equal? temp-file latest-file))
      (when debug
        (println "Content is the same, no need to create a new version"))
      (fs/delete-if-exists temp-file)
      (System/exit 0))

    ;; Get latest version number
    (let [latest-version (get-version latest-file)
          new-version (inc latest-version)]

      ;; Create directory if it doesn't exist
      (fs/create-dirs storage)

      ;; Create new file with incremented version
      (let [new-file (str storage "/" date "-v" new-version ".json")]
        (fs/move temp-file new-file)

        ;; Update the latest symlink
        (fs/delete-if-exists latest-symlink)
        (fs/create-sym-link latest-symlink (fs/file-name new-file))

        (when debug
          (println (str "Created new version: " new-file)))

        ;; fire and forget
        (when handler
          (let [path (System/getProperty "user.dir")
                cmd (-> handler
                        (str/replace #"%o" latest-file)
                        (str/replace #"%n" new-file)
                        (str/replace #"%l" (str "v" latest-version))
                        (str/replace #"%v" (str "v" new-version)))]
            (when debug
              (println "Executing handler" cmd))
            (print (:out (shell/sh "sh" "-c" cmd :dir path)))))))

    (fs/set-last-modified-time ".keeptrack" (java.time.Instant/now))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
