package io.svra.notify;

import org.springframework.data.jpa.repository.JpaRepository;

interface MessageAnchorRepository extends JpaRepository<MessageAnchor, String> {
}
