package code.sandbox

import java.text.SimpleDateFormat

import code.api.APIFailure
import code.metadata.counterparties.{MongoCounterparties, Metadata}
import code.model.{AccountId, BankId}
import code.model.dataAccess._
import code.util.Helper
import code.views.Views
import com.mongodb.QueryBuilder
import net.liftweb.common._
import net.liftweb.mapper.By
import java.util.Date
import net.liftweb.util.Helpers._


case class SandboxBankImport(
  id : String,
  short_name : String,
  full_name : String,
  logo : String,
  website : String)

case class SandboxUserImport(
  email : String,
  password : String,
  display_name : String)

case class SandboxAccountImport(
  id : String,
  bank : String,
  label : String,
  number : String,
  `type` : String,
  balance : SandboxBalanceImport,
  IBAN : String,
  owners : List[String],
  generate_public_view : Boolean)

case class SandboxBalanceImport(
  currency : String,
  amount : String)

case class SandboxTransactionImport(
  id : String,
  this_account : SandboxAccountIdImport,
  counterparty : Option[SandboxAccountIdImport],
  details : SandboxAccountDetailsImport)

case class SandboxAccountIdImport(
  id : String,
  bank : String)

case class SandboxAccountDetailsImport(
  `type` : String,
  description : String,
  posted : String,
  completed : String,
  new_balance : String,
  value : String)

case class SandboxDataImport(
  banks : List[SandboxBankImport],
  users : List[SandboxUserImport],
  accounts : List[SandboxAccountImport],
  transactions : List[SandboxTransactionImport])

object DataImport extends Loggable {


  val datePattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
  val dateFormat = new SimpleDateFormat(datePattern)

  /**
   * @param data
   * @return A full box if the import worked, or else a failure describing what went wrong
   */
  def importData(data : SandboxDataImport) : Box[Unit] = {

    def createBanks() : Box[List[HostedBank]] = {
      val existing = data.banks.flatMap(b => HostedBank.find(BankId(b.id)))

      val allIds = data.banks.map(_.id)
      val emptyIds = allIds.filter(_.isEmpty)
      val uniqueIds = data.banks.map(_.id).distinct
      val duplicateIds = allIds diff uniqueIds

      if(!existing.isEmpty) {
        val existingIds = existing.map(_.permalink.get)
        Failure(s"Bank(s) with id(s) $existingIds already exist (and may have different non-id [e.g. short_name] values).")
      } else if (!emptyIds.isEmpty){
        Failure(s"Bank(s) with empty ids are not allowed")
      } else if(!duplicateIds.isEmpty) {
        Failure(s"Banks must have unique ids. Duplicates found: $duplicateIds")
      }else {
        val hostedBanks = data.banks.map(b => {
          HostedBank.createRecord
            .permalink(b.id)
            .name(b.full_name)
            .alias(b.short_name)
            .website(b.website)
            .logoURL(b.logo)
            .national_identifier(b.id) //this needs to match up with what goes in the OBPEnvelopes
        })

        val validationErrors = hostedBanks.flatMap(_.validate)

        if(!validationErrors.isEmpty) {
          Failure(s"Errors: ${validationErrors.map(_.msg)}")
        } else {
          Full(hostedBanks)
        }
      }
    }

    def createUsers() : Box[List[OBPUser]] = {
      val existing = data.users.flatMap(u => OBPUser.find(By(OBPUser.email, u.email)))
      val allEmails = data.users.map(_.email)
      val duplicateEmails = allEmails diff allEmails.distinct

      if(!existing.isEmpty) {
        val existingEmails = existing.map(_.email.get)
        Failure(s"User(s) with email(s) $existingEmails already exist (and may be different (e.g. different display_name)")
      } else if(!duplicateEmails.isEmpty) {
        Failure(s"Users must have unique emails: Duplicates found: $duplicateEmails")
      }else {

        val obpUsers = data.users.map(u => {
          OBPUser.create
            .email(u.email)
            .lastName(u.display_name)
            .password(u.password)
        })

        val validationErrors = obpUsers.flatMap(_.validate)

        if(!validationErrors.isEmpty) {
          Failure(s"Errors: ${validationErrors.map(_.msg)}")
        } else {
          Full(obpUsers)
        }
      }
    }

    type AccOwnerEmail = String

    //returns unsaved accounts, unsaved views for those accounts
    def createAccounts(createdBanks : List[HostedBank], createdUsers : List[OBPUser]) : Box[List[(Account, List[ViewImpl], List[AccOwnerEmail])]] = {

      val banksNotSpecifiedInImport = data.accounts.flatMap(acc => {
        if(data.banks.exists(b => b.id == acc.bank)) None
        else Some(acc.bank)
      })

      val emptyAccountIds = data.accounts.filter(acc => acc.id.isEmpty)

      case class AccountIdentifier(id : String, bank : String)
      case class AccountNumberForBank(number : String, bank : String)
      val ids = data.accounts.map(acc => AccountIdentifier(acc.id, acc.bank))
      val duplicateIds = ids diff ids.distinct

      val numbers = data.accounts.map(acc =>AccountNumberForBank(acc.number, acc.bank))
      val duplicateNumbers = numbers diff numbers.distinct

      if(!banksNotSpecifiedInImport.isEmpty) {
        Failure(s"Error: one or more accounts specified are for" +
          s" banks not specified in the import data. Unspecified banks: $banksNotSpecifiedInImport)")
      } else if (emptyAccountIds.nonEmpty){
        Failure(s"Error: one or more accounts has an empty id")
      } else if (duplicateIds.nonEmpty){
        val duplicateMsg = duplicateIds.map(d => s"bank id ${d.bank}, account id: ${d.id}").mkString(",")
        Failure(s"Error: accounts at the same bank may not share an id: $duplicateMsg")
      } else if(duplicateNumbers.nonEmpty){
        val duplicateMsg = duplicateNumbers.map(d => s"bank id ${d.bank}, account number: ${d.number}").mkString(",")
        Failure(s"Error: accounts at the same bank may not share account numbers: $duplicateMsg")
      } else {

        def getHostedBank(acc : SandboxAccountImport) =
          Box(createdBanks.find(createdBank => createdBank.permalink.get == acc.bank))

        val existing = data.accounts.flatMap(acc => {
          val hostedBank = getHostedBank(acc).toOption
          hostedBank match {
            case Some(hBank) => {
              val existing = hBank.getAccount(AccountId(acc.id))
              existing.toOption
            }
            case None => None //this is bad (no bank found), but will get handled later on in another check
          }
        })

        if(!existing.isEmpty) {
          val existingAccountAndBankIds = existing.map(e => (s"account id: ${e.permalink.get} bank id: ${e.bankId.value}}"))
          Failure(s"Account(s) to be imported already exist: $existingAccountAndBankIds")
        } else {
          val results = data.accounts.map(acc => {
            val hostedBank = getHostedBank(acc)
            for {
              hBank <- hostedBank
              balance <- tryo{BigDecimal(acc.balance.amount)} ?~ s"Invalid balance: ${acc.balance.amount}"
              ownersNonEmpty <- Helper.booleanToBox(acc.owners.nonEmpty) ?~
                s"Accounts must have at least one owner. Violation: bank id ${acc.bank}, account id ${acc.id}"
              ownersDefinedInDataImport <- Helper.booleanToBox(acc.owners.forall(ownerEmail => data.users.exists(u => u.email == ownerEmail))) ?~ {
                val violations = acc.owners.filter(ownerEmail => !data.users.exists(u => u.email == ownerEmail))
                s"Accounts must have owner(s) defined in data import. Violation: ${violations.mkString(",")}"
              }
            } yield {
              val account = Account.createRecord
                .permalink(acc.id)
                .bankID(hBank.id.get)
                .label(acc.label)
                .currency(acc.balance.currency)
                .balance(balance)
                .number(acc.number)
                .kind(acc.`type`)
                .iban(acc.IBAN)

              val bankId = BankId(acc.bank)
              val accountId = AccountId(acc.id)

              val ownerView = ViewImpl.unsavedOwnerView(bankId, accountId, "Owner View")

              val publicView =
                if(acc.generate_public_view) Some(ViewImpl.createAndSaveDefaultPublicView(bankId, accountId, "Public View"))
                else None

              val views = List(Some(ownerView), publicView).flatten

              (account, views, acc.owners)
            }
          })

          val firstFailure = results.collectFirst{case f: Failure => f}

          firstFailure match {
            case Some(f) => f
            case None => Full(results.flatten) //no failures, so we can return the results
          }

        }
      }

    }

    def createTransactions(createdBanks : List[HostedBank], createdAccounts : List[Account]) : Box[List[(OBPEnvelope, Metadata)]] = {

      def createdAccount(transaction : SandboxTransactionImport) =
        createdAccounts.find(acc =>
          acc.accountId == AccountId(transaction.this_account.id) &&
            acc.bankId == BankId(transaction.this_account.bank))

      val transactionsWithNoAccountSpecifiedInImport = data.transactions.flatMap(t => {
        val createdAcc = createdAccount(t)
        if(createdAcc.isDefined) Some(t)
        else None
      })

      val transactionsWithEmptyIds = data.transactions.filter(_.id.isEmpty)

      case class TransactionIdentifier(id : String, account : String, bank : String)

      val identifiers = data.transactions.map(t => TransactionIdentifier(t.id, t.this_account.id, t.this_account.bank))
      val duplicateIdentifiers = identifiers diff identifiers.distinct

      if(transactionsWithNoAccountSpecifiedInImport.nonEmpty) {
        val identifiers = transactionsWithNoAccountSpecifiedInImport.map(
          t => s"transaction id ${t.id}, account id ${t.this_account.id}, bank id ${t.this_account.bank}")
        Failure(s"Transaction(s) exist with accounts/banks not specified in import data: $identifiers")
      } else if (transactionsWithEmptyIds.nonEmpty) {
        Failure(s"Transaction(s) exist with empty ids")
      } else if(duplicateIdentifiers.nonEmpty) {
        val duplicatesMsg = duplicateIdentifiers.map(i => s"(transaction id : ${i.id}, account id: ${i.account}, bank id: ${i.bank})").mkString(",")
        Failure(s"Transactions for an account must have unique ids. Violations: ${duplicatesMsg} ")
      }else {
        val existing = data.transactions.flatMap(t => {
          for {
            account <- Box(createdAccount(t))
            accountEnvelopesQuery = account.transactionsForAccount
            queryWithTransId = accountEnvelopesQuery.put("transactionId").is(t.id)
            env <- OBPEnvelope.find(queryWithTransId.get)
          } yield (t, env)
        })

        if(!existing.isEmpty) {
          val existingIdentifiers = existing.map {
            case(t, env) => s"transaction id: ${t.id} account id : ${t.this_account.id} bank id : ${t.this_account.bank}"
          }
          Failure(s"Some transactions already exist: $existingIdentifiers")
        } else {

          val envsAndMeta : List[Box[(OBPEnvelope, Metadata)]] = data.transactions.map(t => {

            type CounterpartyAccount = Account
            type CounterpartyBank = HostedBank

            val metadataBox : Box[(Metadata, Option[(CounterpartyAccount, CounterpartyBank)])] = t.counterparty match {
              case Some(counter) => {
                for {
                  counterpartyBank <- Box(createdBanks.find(b => b.permalink.get == counter.bank))//TODO: apifailure?
                  //have to compare a.bankID to createdBank.id instead of just checking a.bankId against t.this_account.bank as createdBank hasn't been
                  //saved so the a.bankId method (which involves a db lookup) will not work
                  counterPartyAccount <- Box(createdAccounts.find(a => a.accountId == AccountId(counter.id) && a.bankID.get.toString == counterpartyBank.id.toString)) ?~
                    s"transaction has counterparty not specified in data import: account id ${counter.id}, bank id ${counter.bank}"
                } yield {
                  val existingMeta = Metadata.find(QueryBuilder.start("originalPartyBankId").is(t.this_account.bank)
                    .put("originalPartyAccountId").is(t.this_account.id)
                    .put("holder").is(counterPartyAccount.label.get).get())

                  (existingMeta.getOrElse{
                    Metadata.createRecord
                      .holder(counterPartyAccount.label.get)
                      .originalPartyAccountId(t.this_account.id)
                      .originalPartyBankId(t.this_account.bank)
                      .publicAlias(MongoCounterparties.newPublicAliasName(BankId(t.this_account.bank), AccountId(t.this_account.id)))
                  }, Some(counterPartyAccount, counterpartyBank))
                }
              }
              case None => {
                Full((Metadata.createRecord
                  .holder(t.details.description)
                  .originalPartyAccountId(t.this_account.id)
                  .originalPartyBankId(t.this_account.bank)
                  .publicAlias(MongoCounterparties.newPublicAliasName(BankId(t.this_account.bank), AccountId(t.this_account.id)))),
                  None)
              }
            }

            for {
              metaAndCounter <- metadataBox
              createdBank <- createdBanks.find(b => b.permalink.get == t.this_account.bank) //TODO: apifailure?
              //have to compare a.bankID to createdBank.id instead of just checking a.bankId against t.this_account.bank as createdBank hasn't been
              //saved so the a.bankId method (which involves a db lookup) will not work
              createdAcc <- createdAccounts.find(a => a.bankID.toString == createdBank.id.get.toString && a.accountId == AccountId(t.this_account.id)) //TODO: apifailure?
              newBalanceValue <- tryo{BigDecimal(t.details.new_balance)} ?~ s"Invalid new balance: ${t.details.new_balance}"
              tValue <- tryo{BigDecimal(t.details.value)} ?~ s"Invalid transaction value: ${t.details.value}"
              postedDate <- tryo{dateFormat.parse(t.details.posted)} ?~ s"Invalid date format: ${t.details.posted}. Expected pattern $datePattern"
              completedDate <-tryo{dateFormat.parse(t.details.completed)} ?~ s"Invalid date format: ${t.details.completed}. Expected pattern $datePattern"
            } yield {

              //bankNationalIdentifier not available from  createdAcc.bankNationalIdentifier as it hasn't been saved so we get it from createdBank
              val obpThisAccountBank = OBPBank.createRecord
                .national_identifier(createdBank.national_identifier.get)

              val obpThisAccount = OBPAccount.createRecord
                .holder(createdAcc.holder.get)
                .number(createdAcc.number.get)
                .kind(createdAcc.kind.get)
                .bank(obpThisAccountBank)

              val obpOtherAccountBank = OBPBank.createRecord
                .national_identifier(metaAndCounter._2.map(_._2.national_identifier.get).getOrElse(""))
                .IBAN(metaAndCounter._2.map(_._1.iban.get).getOrElse(""))

              val obpOtherAccount = OBPAccount.createRecord
                .holder(metaAndCounter._1.holder.get)
                .number(metaAndCounter._2.map(_._1.number.get).getOrElse(""))
                .kind(metaAndCounter._2.map(_._1.kind.get).getOrElse(""))
                .bank(obpOtherAccountBank)

              val newBalance = OBPBalance.createRecord
                .amount(newBalanceValue)
                .currency(createdAcc.currency.get)

              val transactionValue = OBPValue.createRecord
                .amount(tValue)
                .currency(createdAcc.currency.get)

              val obpDetails = OBPDetails.createRecord
                .completed(completedDate)
                .posted(postedDate)
                .kind(t.details.`type`)
                .label(t.details.description)
                .new_balance(newBalance)
                .value(transactionValue)


              val obpTransaction = OBPTransaction.createRecord
                .details(obpDetails)
                .this_account(obpThisAccount)
                .other_account(obpOtherAccount)

              val env = OBPEnvelope.createRecord
                .transactionId(t.id)
                .obp_transaction(obpTransaction)

              (env, metaAndCounter._1)
            }

          })

          //TODO: extract first failure check into method (it's copy pasted from account creation)
          val firstFailure = envsAndMeta.collectFirst{case f: Failure => f}

          firstFailure match {
            case Some(f) => f
            case None => Full(envsAndMeta.flatten) //no failures, so we can return the results
          }
        }

      }

    }

    for {
      banks <- createBanks()
      users <- createUsers()
      accountsResults <- createAccounts(banks, users)
      transactionsAndMetas <- createTransactions(banks, accountsResults.map(_._1))
    } yield {

      //import format has now been verified: we can save everything we created
      banks.foreach(_.save(true))
      users.foreach(_.save())
      accountsResults.foreach{
        case (account, views, accOwnerEmails) =>
          account.save(true)

          val apiUserOwners = users.filter(obpUser => accOwnerEmails.exists(email => email == obpUser.email.get)).flatMap(_.user.obj)
          if(apiUserOwners.size != accOwnerEmails.size) {
            //This shouldn't happen as OBPUser should generate the APIUsers when saved
            logger.error(s"api user(s) not found. Expected: ${accOwnerEmails}, got: ${apiUserOwners} ")
            logger.error("Data import completed with errors.")
          }

          //when bankID was set earlier, the HostedBank had not been saved, so bankId.obj is still caching an Empty value
          //resetting it here fixes that (ugly, but it works)
          account.bankID.set(account.bankID.get)

          val accountHolders = apiUserOwners.map(owner => {
            MappedAccountHolder.create
              .user(owner)
              .accountBankPermalink(account.bankId.value)
              .accountPermalink(account.accountId.value)
          })

          accountHolders.foreach(_.save)

          views.foreach(v => {
            v.save
            //grant users access
            val owners = accountHolders.flatMap(_.user.obj)
            owners.foreach(o => Views.views.vend.addPermission(v, o))
          })
      }
      transactionsAndMetas.foreach {
        case (trans, meta) => {
          meta.save(true)
          trans.save(true)
        }
      }

      Full(Unit)
    }
  }

}
