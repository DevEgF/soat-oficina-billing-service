package com.soat.tech.challenge.oficina.billing.presentation

import com.soat.tech.challenge.oficina.billing.application.CatalogQueryService
import com.soat.tech.challenge.oficina.billing.application.PaymentApplicationService
import com.soat.tech.challenge.oficina.billing.application.QuoteApplicationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/catalog")
@Tag(name = "Catálogo", description = "Serviços e peças com preço (somente leitura; o catálogo entra por migration)")
class CatalogController(private val catalog: CatalogQueryService) {

    @GetMapping("/services")
    @Operation(summary = "Lista os serviços do catálogo")
    fun services(): List<CatalogServiceResponse> = catalog.services().map { it.toResponse() }

    @GetMapping("/parts")
    @Operation(summary = "Lista as peças do catálogo")
    fun parts(): List<CatalogPartResponse> = catalog.parts().map { it.toResponse() }
}

@RestController
@RequestMapping("/api/work-orders/{workOrderId}")
@Tag(name = "Orçamento e pagamento", description = "Orçamento e pagamento de uma OS, pelo id da OS")
class WorkOrderBillingController(
    private val quotes: QuoteApplicationService,
    private val payments: PaymentApplicationService,
) {

    @GetMapping("/quote")
    @Operation(summary = "Consulta o orçamento da OS (404 enquanto não existir)")
    fun quote(@PathVariable workOrderId: UUID): QuoteResponse = quotes.get(workOrderId).toResponse()

    @PostMapping("/quote/decision")
    @Operation(
        summary = "Registra a decisão do cliente",
        description = "approved=true publica QuoteApproved e guarda payment.cardholderName (opcional, padrão APRO) " +
            "para o pagamento; false publica QuoteRejected. Só a partir de PENDING_APPROVAL (senão 409).",
    )
    fun decide(
        @PathVariable workOrderId: UUID,
        @Valid @RequestBody request: QuoteDecisionRequest,
    ): QuoteResponse = quotes.decide(
        workOrderId,
        requireNotNull(request.approved),
        request.reason,
        request.payment?.cardholderName,
    ).toResponse()

    @GetMapping("/payment")
    @Operation(summary = "Consulta o pagamento da OS (404 enquanto não existir)")
    fun payment(@PathVariable workOrderId: UUID): PaymentResponse = payments.get(workOrderId).toResponse()
}
