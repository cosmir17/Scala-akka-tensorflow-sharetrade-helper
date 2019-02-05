# Scala-akka-tensorflow-sharetrade-helper

This project is for fun (the ML part). I wanted to know how it's like using Scala, Akka and Tensorflow together.

Studied 'deep reinforcement learning' chapter in the following book, utilised the Python ML logic linked below.
https://www.manning.com/books/machine-learning-with-tensorflow 
https://github.com/BinRoot/TensorFlow-Book/blob/master/ch08_rl/rl.py

There is Scala version(or wrapper) of Tensorflow
https://github.com/eaplatanios/tensorflow_scala
There is not a book or a course on Udemy for this framework so the ML part is taking much more time than anticipated.

I adapted Akka Actor model to make it distributed in order to enjoy using Akka.
Tensorflow already provides a distribution feature. https://www.tensorflow.org/deploy/distributed (at least the python version does)
Akka Clustering will come later.

Used techologies: Akka persistence, Akka FSM, Akka Router, Akka Untyped, Akka TestKit, Scala, Scala_tensorflow
