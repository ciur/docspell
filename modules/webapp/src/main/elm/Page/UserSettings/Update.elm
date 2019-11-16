module Page.UserSettings.Update exposing (update)

import Page.UserSettings.Data exposing (..)
import Data.Flags exposing (Flags)
import Comp.ChangePasswordForm

update: Flags -> Msg -> Model -> (Model, Cmd Msg)
update flags msg model =
    case msg of
        SetTab t ->
            let
                m = { model | currentTab = Just t }
            in
                (m, Cmd.none)

        ChangePassMsg m ->
            let
                (m2, c2) = Comp.ChangePasswordForm.update flags m model.changePassModel
            in
                ({model | changePassModel = m2}, Cmd.map ChangePassMsg c2)