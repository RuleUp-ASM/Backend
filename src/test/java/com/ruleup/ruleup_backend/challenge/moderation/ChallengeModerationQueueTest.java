package com.ruleup.ruleup_backend.challenge.moderation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationSnapshot.Target;

class ChallengeModerationQueueTest {
    SqsClient sqs=mock(SqsClient.class);
    ChallengeModerationStore store=mock(ChallengeModerationStore.class);
    ChallengeModerationService service=mock(ChallengeModerationService.class);
    @SuppressWarnings("unchecked")
    ChallengeModerationQueue queue() {
        ObjectProvider<SqsClient> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sqs);when(provider.getObject()).thenReturn(sqs);
        return new ChallengeModerationQueue(provider,"https://sqs.test/queue",false,store,service);
    }
    @Test void publicationContainsOnlyRoutingMetadataAndMarksSuccessAfterSend() {
        UUID id=UUID.randomUUID();
        var snapshot=new ChallengeModerationSnapshot(id,UUID.randomUUID(),"private-title","private-description","private-image",List.of(Target.TITLE));
        when(store.read(id)).thenReturn(snapshot);
        queue().publish(id);
        var request=org.mockito.ArgumentCaptor.forClass(SendMessageRequest.class);
        var order=inOrder(sqs,store);
        order.verify(sqs).sendMessage(request.capture());order.verify(store).markEnqueued(snapshot);
        assertThat(request.getValue().messageBody()).contains(id.toString(),"TITLE").doesNotContain("private-");
    }
    @Test void failedPublicationDoesNotMarkEnqueued() {
        UUID id=UUID.randomUUID();
        when(store.read(id)).thenReturn(new ChallengeModerationSnapshot(id,null,"title",null,null,List.of(Target.TITLE)));
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(new IllegalStateException("offline"));
        queue().publish(id);
        verify(store,never()).markEnqueued(any());
    }
    @Test void consumerAcknowledgesCommittedResultsOnly() {
        UUID id=UUID.randomUUID();
        Message message=Message.builder().body("{\"challengeId\":\""+id+"\",\"targets\":[\"TITLE\"]}").receiptHandle("receipt").build();
        doThrow(new IllegalStateException("unavailable")).when(service).moderate(eq(id),anyList());
        queue().handle(message);
        verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
        doNothing().when(service).moderate(eq(id),anyList());
        queue().handle(message);
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }
}
