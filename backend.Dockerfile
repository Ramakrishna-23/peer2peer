FROM openjdk:17 java
LABEL authors="ramakrishnafm"

ENTRYPOINT ["top", "-b"]