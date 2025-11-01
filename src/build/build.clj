(ns build
  (:require
   [clojure.tools.build.api :as b]
   [clojure.string :as str]
   [clojure.java.io :as io]))

(def lib 'dev.freeformsoftware/ejabberd-management-portal)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean
  "Remove the target directory"
  [_]
  (println "Cleaning target directory...")
  (b/delete {:path "target"}))

(defn prep
  "Prepare for build by ensuring prod-resources are built"
  [_]
  (println "Checking for prod-resources...")
  (when-not (.exists (io/file "prod-resources/public/css/output.css"))
    (println "\nWARNING: prod-resources not found!")
    (println "Please run: bin/build-resources")
    (println "This builds the CSS/JS files needed for production.\n")
    (System/exit 1)))

(defn uber
  "Build an uberjar for production deployment"
  [_]
  (println (str "Building uberjar " uber-file "..."))
  (prep nil)
  (clean nil)

  (println "Copying source files...")
  (b/copy-dir {:src-dirs ["src/main" "prod-resources"]
               :target-dir class-dir})

  (println "Compiling Clojure with AOT...")
  (b/compile-clj {:basis @basis
                  :src-dirs ["src/main"]
                  :class-dir class-dir
                  :compile-opts {:direct-linking true}
                  :ns-compile ['dev.freeformsoftware.run.prod]})

  (println "Building uberjar...")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'dev.freeformsoftware.run.prod})

  (println (str "\nUberjar created: " uber-file))
  (println (str "Size: " (format "%.2f MB" (/ (.length (io/file uber-file)) 1024.0 1024.0))))
  (println "\nTo run:")
  (println (str "  java -jar " uber-file))
  (println "\nWith custom config:")
  (println (str "  java -jar " uber-file))
  (println "  (expects /etc/ejabberd-management-portal.edn and /etc/ejabberd-management-portal-secrets.edn)"))

(defn jar
  "Build a thin jar (library jar, not for deployment)"
  [_]
  (println (str "Building thin jar " jar-file "..."))
  (clean nil)

  (println "Copying source files...")
  (b/copy-dir {:src-dirs ["src/main" "prod-resources"]
               :target-dir class-dir})

  (println "Creating jar...")
  (b/jar {:class-dir class-dir
          :jar-file jar-file
          :basis @basis
          :main 'dev.freeformsoftware.run.prod})

  (println (str "\nJar created: " jar-file)))

(defn install
  "Install the jar to local maven repository"
  [_]
  (jar nil)
  (println "Installing to local maven repository...")
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn all
  "Clean, build resources, and create uberjar"
  [_]
  (println "=== Full build process ===\n")
  (println "Step 1: Clean")
  (clean nil)

  (println "\nStep 2: Build resources")
  (println "Running bin/build-resources...")
  (let [result (b/process {:command-args ["bin/build-resources"]})]
    (when (not= 0 (:exit result))
      (println "ERROR: bin/build-resources failed!")
      (System/exit 1)))

  (println "\nStep 3: Build uberjar")
  (uber nil)

  (println "\n=== Build complete! ==="))

(defn -main
  "CLI entry point for builds"
  [& args]
  (let [task (or (first args) "uber")
        task-fn (case task
                  "clean" clean
                  "prep" prep
                  "uber" uber
                  "jar" jar
                  "install" install
                  "all" all
                  (do
                    (println "Unknown task:" task)
                    (println "\nAvailable tasks:")
                    (println "  clean    - Remove target directory")
                    (println "  prep     - Check prod-resources are built")
                    (println "  uber     - Build standalone uberjar (default)")
                    (println "  jar      - Build thin jar")
                    (println "  install  - Install to local maven repo")
                    (println "  all      - Full build: clean + resources + uber")
                    (System/exit 1)))]
    (task-fn nil)))
