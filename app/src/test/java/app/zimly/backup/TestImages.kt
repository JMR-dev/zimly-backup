package app.zimly.backup

import org.testcontainers.utility.DockerImageName

/**
 * Silo, a maintained fork of MinIO, pinned to a release. MinIO no longer publishes its own images
 * (Docker Hub's minio/minio returns 404), so every test that needs a MinIO server runs this in
 * Testcontainers' MinIOContainer, which accepts it as a MinIO substitute.
 */
val SILO_IMAGE: DockerImageName = DockerImageName
    .parse("pgsty/silo:RELEASE.2026-11-12T00-00-00Z")
    .asCompatibleSubstituteFor("minio/minio")
