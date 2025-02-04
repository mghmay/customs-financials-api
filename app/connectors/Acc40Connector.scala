/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package connectors

import config.AppConfig
import domain._
import domain.acc40.{ResponseDetail, SearchAuthoritiesRequest, SearchAuthoritiesResponse}
import models.EORI
import services.{AuditingService, DateTimeService}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class Acc40Connector @Inject()(httpClient: HttpClient,
                               auditingService: AuditingService,
                               appConfig: AppConfig,
                               dateTimeService: DateTimeService,
                               mdgHeaders: MdgHeaders)(implicit executionContext: ExecutionContext) {

  def searchAuthorities(requestingEORI: EORI, searchID: EORI)
    (implicit hc: HeaderCarrier): Future[Either[Acc40Response, AuthoritiesFound]] = {

    val commonRequest = acc40.RequestCommon(
      receiptDate = dateTimeService.currentDateTimeAsIso8601,
      acknowledgementReference = mdgHeaders.acknowledgementReference,
      originatingSystem = "MDTP",
      regime = "CDS"
    )

    val requestDetail = acc40.RequestDetail(
      requestingEORI = requestingEORI,
      searchType = searchType(searchID),
      searchID = searchID
    )

    val request = SearchAuthoritiesRequest(acc40.Request(
      commonRequest,
      requestDetail
    ))

    val result: Future[SearchAuthoritiesResponse] = httpClient.POST[SearchAuthoritiesRequest, SearchAuthoritiesResponse](
      appConfig.acc40SearchAuthoritiesEndpoint,
      request,
      headers = mdgHeaders.headers(appConfig.acc40BearerToken, appConfig.acc40HostHeader)
    )(implicitly, implicitly, HeaderCarrier(), implicitly)

    result.map {
      res => res.searchAuthoritiesResponse.responseDetail match {
        case ResponseDetail(Some(_), _, _, _, _) => Left(ErrorResponse)
        case ResponseDetail(None, Some("0"), _, _, _) => Left(NoAuthoritiesFound)
        case v@ResponseDetail(_, _, _, _, _) => {
          auditingService.auditRequestAuthStatementRequest(v,
            res.searchAuthoritiesResponse.requestDetail)
          Right(v.toAuthoritiesFound)
        }
      }
    }.recover {
      case _ => Left(ErrorResponse)
    }
  }

  def searchType(searchID: EORI) = {
    searchID.value match {
      case x if x.startsWith("GB") => "0"
      case _ => "1"
    }
  }
}
