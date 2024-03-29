package com.dh.g2.apicard.service;

import com.dh.g2.apicard.client.MarginsFeign;
import com.dh.g2.apicard.exceptions.CardException;
import com.dh.g2.apicard.exceptions.MessageError;
import com.dh.g2.apicard.model.CreditCard;
import com.dh.g2.apicard.model.Currency;
import com.dh.g2.apicard.model.Movement;
import com.dh.g2.apicard.repository.ICreditCardRepository;
import com.dh.g2.apicard.repository.IMovementRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class CreditCardService {

    @Autowired
    private ICreditCardRepository creditCardRepository;
    @Autowired
    private MarginsFeign marginsFeign;

    @Autowired
    private IMovementRepository movementRepository;
    /*
    Las operaciones básicas que va tener que implementar este microservicio son:
    ● POST (crear tarjeta con límites) para esto vamos a consumir api-margins
    ● GET (por tipo y número de documento) - consultar tarjetas con sus montos de calificado y disponible
    ● POST (debitar, se debe pasar todos los datos de movimiento, e internamente hacer el débito)
    ● POST(pagar tarjeta, se pasa Numero de tarjeta, Tipo y Número de documento):
       1) Se consulta api-wallet si tiene disponible para pagar
       2) Se debita el dinero de api-wallet
       3) Se devuelve el límite disponible
       4) En caso de no haber disponible se lanza un error.
    */

    @Retry(name = "retry Card")
    @CircuitBreaker(name = "cardCircuitCreation", fallbackMethod = "saveCardFallBack")
    public String save(String idType, String idNumber) throws CardException {

        if (creditCardRepository.findByIdTypeAndIdNumber(idType, idNumber).isPresent()) {
            throw new CardException(MessageError.CUSTOMER_WITH_CARD);
        }
        CreditCard creditCard = new CreditCard();
        creditCard.setIdType(idType);
        creditCard.setIdNumber(idNumber);
        creditCard.setCardNumber(idNumber + Math.random()*Math.pow(10,6));
        MarginsFeign.CalificationDTO calificationDTO = marginsFeign.calculateCalification(creditCard.getIdType(), creditCard.getIdNumber());
        //BigDecimal totalMarginCard = calificationDTO.getSublimits().get(0).getTotalMargin();//.stream().filter(sublimit -> sublimit.getConcept().name().equals(MarginsFeign.CalificationDTO.Concept.CARD)).findFirst().get().getTotalMargin();
        BigDecimal totalMarginCard = calificationDTO.getSublimits().stream().filter(sublimit -> sublimit.getConcept().name().equals("CARD")).findFirst().get().getTotalMargin();
        creditCard.setLimit(totalMarginCard);
        creditCard.setAvailableLimit(totalMarginCard);
        creditCard.setUsedLimit(BigDecimal.ZERO);
        return creditCardRepository.save(creditCard).getIdNumber();
    }


    public CreditCard find(String idType, String idNumber) {
        return creditCardRepository.findByIdTypeAndIdNumber(idType, idNumber).get();
    }

    public void debit(Movement movement) throws CardException {
        BigDecimal amount = movement.getAmount().getValue();
        CreditCard creditCard = creditCardRepository.findByIdTypeAndIdNumber(movement.getDebtCollector().getIdType(), movement.getDebtCollector().getIdNumber()).orElseThrow(() -> new CardException(MessageError.CUSTOMER_NOT_HAVE_CARD));
        creditCard.setUsedLimit(creditCard.getAvailableLimit().add(amount));
        creditCard.setAvailableLimit(creditCard.getAvailableLimit().subtract(amount));
        creditCardRepository.save(creditCard);
        movementRepository.save(movement);
    }

    //TODO Hacer un log de error (log info), además de la exception
    public String saveCardFallBack(String idType, String idNumber, Throwable t) throws CardException{
        throw new CardException(MessageError.CUSTOMER_SERVICE_UNAVAILABLE);
    }

}
