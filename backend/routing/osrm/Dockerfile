FROM ghcr.io/project-osrm/osrm-backend:v26.10.0

ENV DISABLE_ACCESS_LOGGING=1

EXPOSE 5000

COPY start-osrm.sh /usr/local/bin/start-osrm.sh
RUN chmod 0755 /usr/local/bin/start-osrm.sh
USER 10001:10001

CMD ["/usr/local/bin/start-osrm.sh"]
